"""Real local HTTP exercises for the live verification client; no provider is called."""

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

spec = importlib.util.spec_from_file_location("live_verify", Path(__file__).with_name("verify-live-integrations.py"))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


class LiveVerificationTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.mode = "WECHAT_PARTNER"
        self.payment_state = "PAID"
        self.session_valid = False
        self.media = b""
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, data, status=200):
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"code": 1 if status == 200 else 0, "data": data,
                                            "errorCode": "UNAUTHORIZED" if status == 401 else None,
                                            "msg": "do-not-copy-sensitive-server-messages"}).encode())

            def do_GET(self):
                p = urlsplit(self.path).path
                test.requests.append(("GET", self.path))
                if p == "/integrations/capabilities":
                    return self.reply({"paymentMode": test.mode, "sms": True, "maps": True, "media": True, "storage": "oss"})
                if p == "/auth/csrf":
                    self.send_response(200)
                    self.send_header("Set-Cookie", "XSRF-TOKEN=fixture; Path=/")
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    return self.wfile.write(json.dumps({"code": 1, "data": {"token": "fixture", "headerName": "X-XSRF-TOKEN"}}).encode())
                if p == "/auth/me":
                    return self.reply({"role": "CUSTOMER"}, 200 if test.session_valid else 401)
                if p == "/auth/phone":
                    return self.reply({"verified": True, "phone": "138****0000"})
                if p.startswith("/payments/"):
                    return self.reply({"id": "pay-fixture", "mode": test.mode, "status": test.payment_state})
                if p == "/media/image-fixture":
                    self.send_response(200)
                    self.end_headers()
                    return self.wfile.write(test.media)
                if p == "/platform/refunds":
                    test.assertEqual("management", self.headers.get("X-Session-Type"))
                    return self.reply([{"id": "refund-fixture", "status": "SUCCESS"}])
                return self.reply(None, 404)

            def do_POST(self):
                p = urlsplit(self.path).path
                test.requests.append(("POST", self.path))
                payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                test.assertEqual("fixture", self.headers.get("X-XSRF-TOKEN"))
                test.assertIn("XSRF-TOKEN=fixture", self.headers.get("Cookie", ""))
                if p == "/maps/geocode":
                    return self.reply([{"formattedAddress": "private-address", "location": "116.4,39.9"}])
                if p == "/maps/route":
                    return self.reply({"distance": 800, "duration": 300})
                if p in {"/auth/login", "/admin/login"}:
                    data = json.loads(payload)
                    test.assertEqual("secret-verification-password", data["password"])
                    test.session_valid = True
                    self.send_response(200)
                    self.send_header("Set-Cookie", "ao_user=private-session-cookie; HttpOnly; Path=/")
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    return self.wfile.write(json.dumps({"code": 1, "data": {"role": "PLATFORM_ADMIN" if p == "/admin/login" else "CUSTOMER"}}).encode())
                if p == "/auth/phone/challenges":
                    return self.reply({"challengeId": "challenge-fixture", "status": "REQUEST_ACCEPTED"})
                if p == "/auth/phone":
                    data = json.loads(payload)
                    test.assertEqual("123456", data["code"])
                    test.session_valid = False
                    return self.reply(None)
                if p == "/payments":
                    return self.reply({"id": "pay-fixture", "mode": test.mode, "status": "PENDING",
                                       "action": {"type": "QR", "payload": "weixin://private-signed-payment"}})
                if p == "/media":
                    boundary = self.headers["Content-Type"].split("boundary=", 1)[1].encode()
                    image_part = [part for part in payload.split(b"--" + boundary) if b'name="image"' in part][0]
                    test.media = image_part.split(b"\r\n\r\n", 1)[1][:-2]
                    return self.reply({"id": "image-fixture", "url": "/media/image-fixture"})
                if p == "/platform/reconciliation":
                    return self.reply({"batches": [{"bill_date": "2026-11-05", "status": "MATCHED",
                                                     "checksum": "a" * 64, "difference_count": 0}], "differences": []})
                return self.reply(None, 404)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.env = {"LIVE_VERIFY_USERNAME": "fixture", "LIVE_VERIFY_PASSWORD": "secret-verification-password", "LIVE_VERIFY_ROLE": "CUSTOMER"}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_verification(self, *extra, **kwargs):
        args = live.parser().parse_args(["--base-url", self.base, *extra])
        return live.verify(args, self.env, **kwargs)

    def test_default_reads_capabilities_only_and_never_claims_live_success(self):
        report = self.run_verification()
        self.assertEqual("NOT_RUN", report["status"])
        self.assertEqual([("GET", "/integrations/capabilities")], self.requests)
        self.assertEqual("APPLICATION_CONFIGURATION_ONLY", report["checks"]["capabilities"]["evidence"])

    def test_explicit_map_and_route_execute_actual_authenticated_http(self):
        report = self.run_verification("--map-address", "private-address", "--route-origin", "116.4,39.9", "--route-destination", "116.5,39.8")
        self.assertEqual("PASS", report["status"])
        self.assertEqual(800, report["checks"]["route"]["distanceMeters"])
        for secret in ("private-address", "secret-verification-password", "private-session-cookie"):
            self.assertNotIn(secret, json.dumps(report))

    def test_upload_reads_the_real_multipart_response_back_and_compares_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            image = Path(folder) / "photo.png"
            image.write_bytes(b"\x89PNG\r\n\x1a\ncontrolled-fixture-bytes")
            report = self.run_verification("--upload-image", str(image), "--resource-id", "7")
        self.assertEqual("PASS", report["status"])
        self.assertEqual("oss", report["checks"]["media"]["provider"])
        self.assertEqual("UPLOADED_AND_READ_BACK", report["checks"]["media"]["evidence"])

    def test_sms_acceptance_is_pending_not_a_delivery_pass(self):
        report = self.run_verification("--sms-phone", "13800000000")
        self.assertEqual("PENDING", report["status"])
        self.assertEqual("PENDING", report["checks"]["smsRequest"]["status"])
        self.assertNotIn("13800000000", json.dumps(report))
        self.assertEqual("NOT_RUN", report["checks"]["phoneBinding"]["status"])

    def test_interactive_received_code_verifies_binding_and_invalidated_session(self):
        report = self.run_verification("--challenge-id", "challenge-fixture", "--code", code_reader=lambda _: "123456")
        self.assertEqual("PASS", report["status"])
        self.assertEqual("PASS", report["checks"]["sessionInvalidation"]["status"])
        self.assertNotIn("123456", json.dumps(report))

    def test_payment_creates_a_session_and_only_queries_afterward(self):
        report = self.run_verification("--checkout-id", "checkout-fixture", "--poll-seconds", "0")
        self.assertEqual("PASS", report["checks"]["payment"]["status"])
        self.assertIn(("POST", "/payments"), self.requests)
        self.assertFalse(any("/pay" in url and url != "/payments" and not url.startswith("/payments/") for _, url in self.requests))
        self.assertNotIn("weixin://private-signed-payment", json.dumps(report))

    def test_pending_payment_is_not_pass_and_simulation_never_calls_real_session(self):
        self.payment_state = "UNKNOWN"
        report = self.run_verification("--payment-id", "pay-fixture", "--poll-seconds", "0")
        self.assertEqual("PENDING", report["status"])
        self.payment_state = "REFUNDED"
        report = self.run_verification("--payment-id", "pay-fixture", "--poll-seconds", "0")
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("PAYMENT_REFUNDED_WITHOUT_FULFILLMENT", report["checks"]["payment"]["reason"])
        self.mode = "SIMULATED"
        self.requests.clear()
        report = self.run_verification("--checkout-id", "checkout-fixture", "--poll-seconds", "0")
        self.assertEqual("FAIL", report["status"])
        self.assertNotIn(("POST", "/payments"), self.requests)

    def test_platform_refund_and_bill_are_verified_without_submitting_refunds(self):
        self.env["LIVE_VERIFY_ROLE"] = "PLATFORM_ADMIN"
        report = self.run_verification("--refund-id", "refund-fixture", "--bill-date", "2026-11-05")
        self.assertEqual("PASS", report["status"])
        self.assertEqual("SUCCESS", report["checks"]["refund"]["channelState"])
        self.assertFalse(any(method == "POST" and "/refund" in url for method, url in self.requests))

    def test_remote_plain_http_is_rejected_before_credentials_are_sent(self):
        with self.assertRaises(live.VerificationError):
            live.Client("http://example.com")
        with self.assertRaises(live.VerificationError):
            live.Client("https://username:secret@example.com")


if __name__ == "__main__":
    unittest.main()
