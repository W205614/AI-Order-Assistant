"""Explicit, redacted acceptance against configured services through the real application.

No arguments that select an operation means a configuration read only. No model calls,
refund submissions or automatic payment confirmation are performed.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import getpass
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from integration_settings import write_private_json

CHECKS = (
    "capabilities", "authentication", "geocode", "route", "media", "smsRequest",
    "phoneBinding", "sessionInvalidation", "paymentSession", "payment", "refund", "reconciliation",
)


class VerificationError(Exception):
    def __init__(self, code, http_status=None):
        self.code = code
        self.http_status = http_status
        super().__init__(code)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, base, management=False, ca_file=None):
        url = urllib.parse.urlsplit(base)
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
            raise VerificationError("INVALID_BASE_URL")
        if url.path not in {"", "/"} or url.query or url.fragment:
            raise VerificationError("BASE_URL_MUST_BE_APPLICATION_ORIGIN")
        if url.scheme == "http" and url.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise VerificationError("HTTPS_REQUIRED_FOR_REMOTE_VERIFICATION")
        self.base = base.rstrip("/")
        self.management = management
        self.csrf = None
        self.csrf_header = "X-XSRF-TOKEN"
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca_file)),
        )

    def raw(self, method, path, body=None, content_type="application/json", limit=2 * 1024 * 1024, timeout=20):
        if not path.startswith("/") or path.startswith("//"):
            raise VerificationError("INVALID_APPLICATION_PATH")
        if method not in {"GET", "HEAD"} and self.csrf is None:
            self.refresh_csrf()
        headers = {"Accept": "application/json"}
        if self.management:
            headers["X-Session-Type"] = "management"
        if body is not None:
            headers["Content-Type"] = content_type
        if method not in {"GET", "HEAD"}:
            headers[self.csrf_header] = self.csrf
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                payload = response.read(limit + 1)
                if len(payload) > limit:
                    raise VerificationError("RESPONSE_TOO_LARGE")
                return payload
        except urllib.error.HTTPError as failure:
            try:
                result = json.loads(failure.read(32768))
                code = result.get("errorCode", "")
            except (ValueError, AttributeError):
                code = ""
            safe = code if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", code) else "HTTP_ERROR"
            raise VerificationError(safe, failure.code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise VerificationError("NETWORK_UNAVAILABLE") from None

    def api(self, method, path, body=None, timeout=20):
        payload = self.raw(method, path, None if body is None else json.dumps(body).encode("utf-8"), timeout=timeout)
        try:
            result = json.loads(payload)
        except (ValueError, UnicodeError):
            raise VerificationError("INVALID_JSON_RESPONSE") from None
        if not isinstance(result, dict) or type(result.get("code")) is not int or result["code"] != 1:
            raise VerificationError("APPLICATION_REJECTED")
        return result.get("data")

    def refresh_csrf(self):
        data = self.api("GET", "/auth/csrf")
        if not isinstance(data, dict) or not isinstance(data.get("token"), str):
            raise VerificationError("INVALID_CSRF_RESPONSE")
        self.csrf = data["token"]
        self.csrf_header = data.get("headerName", "X-XSRF-TOKEN")

    def login(self, username, password):
        self.refresh_csrf()
        result = self.api("POST", "/admin/login" if self.management else "/auth/login",
                          {"username": username, "password": password})
        self.refresh_csrf()
        return result


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base-url", default="http://localhost:9090")
    p.add_argument("--ca-file", help="Optional CA bundle for the application's HTTPS endpoint")
    p.add_argument("--report", type=Path, help="Write a redacted JSON report; stdout is always JSON")
    p.add_argument("--map-address", help="Explicitly invoke live geocoding; may consume provider quota")
    p.add_argument("--route-origin", help="GCJ02 longitude,latitude; explicitly invoke live routing")
    p.add_argument("--route-destination", help="GCJ02 longitude,latitude")
    p.add_argument("--upload-image", type=Path, help="Upload and read back this image; creates a media asset")
    p.add_argument("--media-purpose", choices=["DISH", "MERCHANT", "AFTERSALE"], default="DISH")
    p.add_argument("--resource-id", help="An existing resource owned by the verification account")
    p.add_argument("--sms-phone", help="Explicitly request a binding SMS; may incur SMS charges")
    p.add_argument("--account-type", choices=["CUSTOMER", "MANAGEMENT"])
    p.add_argument("--challenge-id", help="Previously requested phone binding challenge")
    p.add_argument("--code", action="store_true", help="Read the SMS code securely with getpass; do not pass its value on the command line")
    payments = p.add_mutually_exclusive_group()
    payments.add_argument("--checkout-id", help="Create a real payment session for an existing unpaid checkout; a person must pay in the browser")
    payments.add_argument("--payment-id", help="Only poll an existing payment session")
    p.add_argument("--scene", choices=["NATIVE", "H5", "JSAPI"], default="NATIVE")
    p.add_argument("--poll-seconds", type=int, choices=range(0, 121), metavar="0..120", default=120)
    p.add_argument("--refund-id", help="Only inspect an existing refund in the platform's recent refund list")
    p.add_argument("--bill-date", help="Explicitly fetch and reconcile a provider bill, YYYY-MM-DD; platform account required")
    return p


def multipart(image, purpose, resource):
    if not image.is_file():
        raise VerificationError("IMAGE_FILE_NOT_FOUND")
    if image.stat().st_size > 5 * 1024 * 1024:
        raise VerificationError("IMAGE_TOO_LARGE")
    payload = image.read_bytes()
    if payload.startswith(b"\x89PNG\r\n\x1a\n"):
        mime, suffix = "image/png", "png"
    elif payload.startswith(b"\xff\xd8\xff"):
        mime, suffix = "image/jpeg", "jpg"
    else:
        raise VerificationError("UNSUPPORTED_IMAGE_TYPE")
    boundary = "live-verification-" + uuid.uuid4().hex
    parts = []
    for name, value in (("purpose", purpose), ("resourceId", resource)):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="verification.{suffix}"\r\nContent-Type: {mime}\r\n\r\n'.encode())
    parts += [payload, f"\r\n--{boundary}--\r\n".encode()]
    return b"".join(parts), "multipart/form-data; boundary=" + boundary, hashlib.sha256(payload).hexdigest()


def poll_payment(client, payment_id, seconds):
    deadline = time.monotonic() + seconds
    while True:
        result = client.api("GET", "/payments/" + urllib.parse.quote(payment_id, safe=""),
                            timeout=max(0.1, min(20, deadline - time.monotonic())) if seconds else 20)
        if not isinstance(result, dict) or result.get("mode") != "WECHAT_PARTNER":
            raise VerificationError("REAL_PAYMENT_MODE_REQUIRED")
        state = result.get("status")
        if state == "PAID":
            return {"status": "PASS", "evidence": "PROVIDER_PAYMENT_CONFIRMED", "paymentId": payment_id}
        if state in {"CLOSED", "REFUNDED"}:
            return {"status": "FAIL", "reason": "PAYMENT_CLOSED_WITHOUT_SUCCESS" if state == "CLOSED" else "PAYMENT_REFUNDED_WITHOUT_FULFILLMENT", "paymentId": payment_id}
        if state in {"REVIEW", "REFUNDING"} or time.monotonic() >= deadline:
            return {"status": "PENDING", "reason": "PAYMENT_NOT_CONFIRMED", "channelState": state, "paymentId": payment_id}
        time.sleep(min(3, max(0, deadline - time.monotonic())))


def verify(args, environ=None, code_reader=None):
    env = os.environ if environ is None else environ
    results = {name: {"status": "NOT_RUN"} for name in CHECKS}
    report = {"schemaVersion": 1, "checkedAt": datetime.now(timezone.utc).isoformat(),
              "status": "NOT_RUN", "checks": results}
    requested = bool(args.map_address or args.route_origin or args.route_destination or args.upload_image or args.sms_phone
                     or args.challenge_id or args.checkout_id or args.payment_id or args.refund_id or args.bill_date)
    role = env.get("LIVE_VERIFY_ROLE", "CUSTOMER")
    if role not in {"CUSTOMER", "OWNER", "STAFF", "PLATFORM_ADMIN", "RIDER"}:
        raise VerificationError("INVALID_VERIFICATION_ROLE")
    management = role != "CUSTOMER"
    client = Client(args.base_url, management, args.ca_file)
    try:
        caps = client.api("GET", "/integrations/capabilities")
        if not isinstance(caps, dict) or caps.get("paymentMode") not in {"SIMULATED", "WECHAT_PARTNER"}:
            raise VerificationError("INVALID_CAPABILITY_RESPONSE")
        results["capabilities"] = {"status": "PASS", "evidence": "APPLICATION_CONFIGURATION_ONLY",
                                   "paymentMode": caps["paymentMode"], "storage": caps.get("storage", "unknown")}
    except VerificationError as failure:
        results["capabilities"] = {"status": "FAIL", "reason": failure.code}
        report["status"] = "FAIL"
        return report
    if not requested:
        return report
    username, password = env.get("LIVE_VERIFY_USERNAME", ""), env.get("LIVE_VERIFY_PASSWORD", "")
    if not username or not password:
        results["authentication"] = {"status": "FAIL", "reason": "LIVE_VERIFICATION_CREDENTIALS_REQUIRED"}
        report["status"] = "FAIL"
        return report
    try:
        identity = client.login(username, password)
        if not isinstance(identity, dict) or identity.get("role") != role:
            raise VerificationError("ACCOUNT_ROLE_MISMATCH")
        results["authentication"] = {"status": "PASS", "role": role}
    except VerificationError as failure:
        results["authentication"] = {"status": "FAIL", "reason": failure.code}
        report["status"] = "FAIL"
        return report

    def perform(name, action):
        try:
            results[name] = action()
        except VerificationError as failure:
            results[name] = {"status": "FAIL", "reason": failure.code}
            if failure.http_status is not None:
                results[name]["httpStatus"] = failure.http_status
        except (OSError, ValueError, TypeError, KeyError):
            results[name] = {"status": "FAIL", "reason": "LOCAL_INPUT_OR_RESPONSE_INVALID"}

    if args.map_address:
        def geocode():
            candidates = client.api("GET", "/maps/geocode?q=" + urllib.parse.quote(args.map_address, safe=""))
            if not isinstance(candidates, list) or not candidates or not all(
                isinstance(item, dict) and isinstance(item.get("location"), str)
                and re.fullmatch(r"-?[\d.]+,-?[\d.]+", item["location"]) for item in candidates
            ):
                raise VerificationError("NO_VALID_MAP_CANDIDATE")
            return {"status": "PASS", "evidence": "LIVE_GEOCODE_RESULT", "candidateCount": len(candidates)}
        perform("geocode", geocode)
    if args.route_origin or args.route_destination:
        def route():
            if not args.route_origin or not args.route_destination:
                raise VerificationError("BOTH_ROUTE_COORDINATES_REQUIRED")
            data = client.api("GET", "/maps/route?" + urllib.parse.urlencode({"origin": args.route_origin, "destination": args.route_destination}))
            if not isinstance(data, dict) or not isinstance(data.get("distance"), (int, float)) or not isinstance(data.get("duration"), (int, float)):
                raise VerificationError("INVALID_ROUTE_RESULT")
            return {"status": "PASS", "evidence": "LIVE_ROUTE_RESULT", "distanceMeters": data["distance"], "durationSeconds": data["duration"]}
        perform("route", route)
    if args.upload_image:
        def media():
            if not args.resource_id:
                raise VerificationError("MEDIA_RESOURCE_REQUIRED")
            body, content_type, expected = multipart(args.upload_image, args.media_purpose, args.resource_id)
            raw = client.raw("POST", "/media", body, content_type)
            try:
                result = json.loads(raw)
                data = result["data"]
                if result.get("code") != 1 or not isinstance(data, dict):
                    raise ValueError()
                path = data["url"]
                if not isinstance(path, str) or not path.startswith("/media/") or urllib.parse.urlsplit(path).netloc:
                    raise ValueError()
            except (ValueError, KeyError, TypeError):
                raise VerificationError("INVALID_MEDIA_RESPONSE") from None
            downloaded = client.raw("GET", path, limit=5 * 1024 * 1024)
            if hashlib.sha256(downloaded).hexdigest() != expected:
                raise VerificationError("MEDIA_READBACK_MISMATCH")
            return {"status": "PASS", "evidence": "UPLOADED_AND_READ_BACK", "provider": caps.get("storage", "unknown"), "sha256": expected}
        perform("media", media)
    if args.sms_phone:
        def sms():
            expected_type = "MANAGEMENT" if management else "CUSTOMER"
            if args.account_type and args.account_type != expected_type:
                raise VerificationError("ACCOUNT_TYPE_MISMATCH")
            data = client.api("POST", "/auth/phone/challenges", {"purpose": "BIND", "phone": args.sms_phone, "accountType": expected_type})
            if not isinstance(data, dict) or not isinstance(data.get("challengeId"), str):
                raise VerificationError("INVALID_SMS_CHALLENGE")
            return {"status": "PENDING", "evidence": "CHANNEL_REQUEST_ACCEPTED_ONLY",
                    "reason": "HANDSET_RECEIPT_NOT_YET_VERIFIED", "challengeId": data["challengeId"]}
        perform("smsRequest", sms)
    if args.challenge_id:
        def bind():
            if not args.code:
                raise VerificationError("INTERACTIVE_CODE_FLAG_REQUIRED")
            reader = code_reader
            if reader is None:
                if not sys.stdin.isatty():
                    raise VerificationError("SECURE_INTERACTIVE_INPUT_REQUIRED")
                reader = getpass.getpass
            code = reader("请输入手机实际收到的验证码（不回显）：")
            if not re.fullmatch(r"[0-9]{6}", code):
                raise VerificationError("INVALID_CODE_FORMAT")
            client.api("POST", "/auth/phone", {"challengeId": args.challenge_id, "code": code, "currentPassword": password})
            try:
                client.api("GET", "/auth/me")
            except VerificationError as failure:
                if failure.http_status != 401:
                    raise
                results["sessionInvalidation"] = {"status": "PASS", "evidence": "OLD_SESSION_REJECTED"}
            else:
                results["sessionInvalidation"] = {"status": "FAIL", "reason": "OLD_SESSION_STILL_VALID"}
            client.login(username, password)
            current = client.api("GET", "/auth/phone")
            if not isinstance(current, dict) or current.get("verified") is not True:
                raise VerificationError("PHONE_BINDING_NOT_CONFIRMED")
            return {"status": "PASS", "evidence": "USER_RECEIVED_CODE_VERIFIED_AND_BOUND"}
        perform("phoneBinding", bind)
    if args.checkout_id or args.payment_id:
        def payment():
            if caps["paymentMode"] != "WECHAT_PARTNER":
                raise VerificationError("REAL_PAYMENT_MODE_REQUIRED")
            payment_id = args.payment_id
            if args.checkout_id:
                session = client.api("POST", "/payments", {"sourceType": "CHECKOUT", "sourceId": args.checkout_id, "scene": args.scene})
                if not isinstance(session, dict) or session.get("mode") != "WECHAT_PARTNER" or not isinstance(session.get("id"), str):
                    raise VerificationError("INVALID_REAL_PAYMENT_SESSION")
                payment_id = session["id"]
                results["paymentSession"] = {"status": "PASS", "evidence": "REAL_PROVIDER_SESSION_CREATED", "paymentId": payment_id, "scene": args.scene}
            print("请在浏览器打开 " + client.base + "/campus/，使用同一账号核对已有订单并亲自支付；脚本只查询结果。", file=sys.stderr)
            return poll_payment(client, payment_id, args.poll_seconds)
        perform("payment", payment)
        if results["payment"].get("reason") == "WECHAT_OPENID_REQUIRED":
            results["payment"] = {"status": "PENDING", "reason": "WECHAT_BROWSER_OAUTH_REQUIRED"}
            print("请在微信内登录校园页，点击支付完成公众号授权，再运行本次检查。", file=sys.stderr)
    if args.refund_id:
        def refund():
            if role != "PLATFORM_ADMIN":
                raise VerificationError("PLATFORM_ACCOUNT_REQUIRED")
            rows = client.api("GET", "/platform/refunds?limit=200")
            if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
                raise VerificationError("INVALID_REFUND_LIST")
            matches = [row for row in rows if row.get("id") == args.refund_id]
            if not matches:
                return {"status": "PENDING", "reason": "REFUND_NOT_IN_RECENT_PLATFORM_LIST"}
            state = matches[0].get("status")
            return {"status": "PASS" if state == "SUCCESS" else "PENDING", "evidence": "RECORDED_PROVIDER_REFUND_STATE", "channelState": state}
        perform("refund", refund)
    if args.bill_date:
        def bill():
            if role != "PLATFORM_ADMIN":
                raise VerificationError("PLATFORM_ACCOUNT_REQUIRED")
            try:
                datetime.strptime(args.bill_date, "%Y-%m-%d")
            except ValueError:
                raise VerificationError("INVALID_BILL_DATE") from None
            result = client.api("POST", "/platform/reconciliation?" + urllib.parse.urlencode({"date": args.bill_date}))
            if not isinstance(result, dict) or not isinstance(result.get("batches"), list) or not all(isinstance(row, dict) for row in result["batches"]):
                raise VerificationError("INVALID_RECONCILIATION_RESPONSE")
            batches = [item for item in result["batches"] if item.get("bill_date") == args.bill_date]
            if not batches:
                return {"status": "PENDING", "reason": "NO_PROVIDER_BILL_EVIDENCE"}
            matched = all(item.get("status") == "MATCHED" and re.fullmatch(r"[0-9a-fA-F]{64}", item.get("checksum") or "") for item in batches)
            return {"status": "PASS" if matched else "PENDING", "evidence": "PROVIDER_BILL_RECONCILED" if matched else "BILL_NOT_FULLY_MATCHED",
                    "batchCount": len(batches), "differenceCount": sum(int(item.get("difference_count", 0)) for item in batches)}
        perform("reconciliation", bill)
    statuses = [item["status"] for name, item in results.items() if name not in {"capabilities", "authentication"}]
    report["status"] = "FAIL" if "FAIL" in statuses else "PENDING" if "PENDING" in statuses else "PASS"
    return report


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        report = verify(args)
    except VerificationError as failure:
        report = {"schemaVersion": 1, "status": "FAIL", "reason": failure.code}
    except (OSError, ValueError, TypeError, KeyError):
        report = {"schemaVersion": 1, "status": "FAIL", "reason": "LOCAL_INPUT_OR_RESPONSE_INVALID"}
    except (EOFError, KeyboardInterrupt):
        report = {"schemaVersion": 1, "status": "PENDING", "reason": "VERIFICATION_CANCELLED"}
    if args.report:
        write_private_json(args.report, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if report["status"] == "FAIL" else 2 if report["status"] == "PENDING" else 0


if __name__ == "__main__":
    raise SystemExit(main())
