"""Validate the real Caddyfile's body limits and forwarding trust boundary.

No Compose service, business database, external endpoint, or model is used.
Docker must be available; missing standard test images are pulled explicitly.
"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 128 * 1024
CADDY_IMAGE = os.getenv(
    "CADDY_TEST_IMAGE",
    "caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f",
)
UPSTREAM_IMAGE = "python:3.13-alpine"
UPSTREAM = r"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os

class Echo(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == "/headers":
            result = json.dumps({"headers": {name.lower(): value for name, value in self.headers.items()}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(result)))
            self.end_headers()
            self.wfile.write(result)
            return
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def do_POST(self):
        received = 0
        try:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                while True:
                    line = self.rfile.readline()
                    if not line:
                        return
                    size = int(line.split(b";", 1)[0].strip(), 16)
                    if not size:
                        while self.rfile.readline().strip():
                            pass
                        break
                    chunk = self.rfile.read(size)
                    if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                        return
                    received += size
            else:
                expected = int(self.headers["Content-Length"])
                received = len(self.rfile.read(expected))
                if received != expected:
                    return
            result = json.dumps({"received": received}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(result)))
            self.end_headers()
            self.wfile.write(result)
        except (BrokenPipeError, ConnectionResetError):
            pass

ThreadingHTTPServer(("0.0.0.0", 9090), Echo).serve_forever()
"""
CLIENT = r"""
import http.client
import json
import os
import sys

connection = http.client.HTTPConnection("proxy", 8080, timeout=10)
try:
    client_address = None
    if sys.argv[1] == "ready":
        connection.request("GET", "/")
    elif sys.argv[1] == "headers":
        headers = json.loads(sys.argv[2])
        headers["Host"] = "campus.example:8080"
        connection.connect()
        client_address = connection.sock.getsockname()[0]
        connection.request("GET", "/headers", headers=headers)
    else:
        size = int(sys.argv[1])
        path = sys.argv[3] if len(sys.argv) > 3 else "/echo"
        headers = {"Content-Type": sys.argv[4]} if len(sys.argv) > 4 else {}
        if sys.argv[2] == "chunked":
            chunks = [b"x" * min(8192, size - offset) for offset in range(0, size, 8192)]
            connection.request("POST", path, body=iter(chunks), headers=headers, encode_chunked=True)
        else:
            connection.request("POST", path, body=b"x" * size, headers=headers)
    response = connection.getresponse()
    result = {"status": response.status, "body": response.read().decode("utf-8")}
    if client_address is not None:
        result["clientAddress"] = client_address
    print(json.dumps(result))
finally:
    connection.close()
"""


def docker(*args, check=True):
    result = subprocess.run(
        ["docker", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
    )
    if check and result.returncode:
        raise RuntimeError(f"Disposable Docker test command failed: {args[0]}\n{result.stderr}")
    return result


class CaddyBodyLimitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = "ai-order-caddy-test-" + uuid.uuid4().hex
        cls.network = cls.base + "-net"
        cls.proxy = cls.base + "-proxy"
        cls.upstream = cls.base + "-upstream"
        cls.temp = tempfile.TemporaryDirectory(prefix=cls.base + "-")
        try:
            for image in (CADDY_IMAGE, UPSTREAM_IMAGE):
                if docker("image", "inspect", image, check=False).returncode:
                    docker("pull", image)
            config_mount = f"{ROOT / 'deploy/Caddyfile'}:/etc/caddy/Caddyfile:ro"
            docker(
                "run",
                "--rm",
                "--network",
                "none",
                "--read-only",
                "--tmpfs",
                "/config",
                "--tmpfs",
                "/data",
                "-e",
                "APP_DOMAIN=http://:8080",
                "-v",
                config_mount,
                CADDY_IMAGE,
                "caddy",
                "validate",
                "--config",
                "/etc/caddy/Caddyfile",
                "--adapter",
                "caddyfile",
            )
            server = Path(cls.temp.name) / "echo.py"
            server.write_text(UPSTREAM, encoding="utf-8")
            client = Path(cls.temp.name) / "client.py"
            client.write_text(CLIENT, encoding="utf-8")
            docker("network", "create", "--internal", cls.network)
            docker(
                "run",
                "-d",
                "--name",
                cls.upstream,
                "--network",
                cls.network,
                "--network-alias",
                "gateway",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "-v",
                f"{server}:/echo.py:ro",
                "-v",
                f"{client}:/client.py:ro",
                UPSTREAM_IMAGE,
                "python",
                "-B",
                "/echo.py",
            )
            docker(
                "run",
                "-d",
                "--name",
                cls.proxy,
                "--network",
                cls.network,
                "--network-alias",
                "proxy",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--cap-add",
                "NET_BIND_SERVICE",
                "--security-opt",
                "no-new-privileges",
                "--tmpfs",
                "/config",
                "--tmpfs",
                "/data",
                "-e",
                "APP_DOMAIN=http://:8080",
                "-v",
                config_mount,
                CADDY_IMAGE,
            )
            # Client runs inside the isolated network: no host port or external route.
            until = time.monotonic() + 20
            while True:
                try:
                    ready = json.loads(
                        docker("exec", cls.upstream, "python", "-B", "/client.py", "ready").stdout
                    )
                    if ready["status"] == 200:
                        break
                except RuntimeError:
                    pass
                if time.monotonic() >= until:
                    raise RuntimeError("Disposable proxy/upstream did not become ready")
                time.sleep(0.1)
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        # Remove exact unique names only; no prune, Compose down, or business volumes.
        for name in (cls.proxy, cls.upstream):
            docker("rm", "-f", name, check=False)
        docker("network", "rm", cls.network, check=False)
        cls.temp.cleanup()

    def post(self, size, *, chunked=False, path="/echo", content_type="application/json"):
        result = json.loads(
            docker(
                "exec",
                self.upstream,
                "python",
                "-B",
                "/client.py",
                str(size),
                "chunked" if chunked else "length",
                path,
                content_type,
            ).stdout
        )
        return result["status"], result["body"]

    def test_normal_and_boundary_bodies_pass(self):
        for chunked in (False, True):
            for size in (64, LIMIT):
                with self.subTest(chunked=chunked, size=size):
                    status, content = self.post(size, chunked=chunked)
                    self.assertEqual(status, 200)
                    self.assertEqual(json.loads(content)["received"], size)

    def test_image_upload_limit_only_applies_to_exact_multipart_route(self):
        for chunked in (False, True):
            status, _ = self.post(5 * 1024 * 1024, chunked=chunked, path="/media",
                                  content_type="multipart/form-data; boundary=contract")
            self.assertEqual(status, 200)
            status, _ = self.post(6 * 1024 * 1024 + 1, chunked=chunked, path="/media",
                                  content_type="multipart/form-data; boundary=contract")
            self.assertEqual(status, 413)
            for path, content_type in (("/media", "application/json"),
                                       ("/media/other", "multipart/form-data; boundary=contract")):
                status, _ = self.post(LIMIT + 1, chunked=chunked, path=path, content_type=content_type)
                self.assertEqual(status, 413)

    def test_content_length_and_chunked_over_limit_return_413(self):
        for chunked in (False, True):
            with self.subTest(chunked=chunked):
                status, _ = self.post(LIMIT + 1, chunked=chunked)
                self.assertEqual(status, 413)

    def forwarded_headers(self, headers):
        result = json.loads(
            docker("exec", self.upstream, "python", "-B", "/client.py", "headers",
                   json.dumps(headers)).stdout
        )
        self.assertEqual(result["status"], 200)
        return result["clientAddress"], json.loads(result["body"])["headers"]

    def test_proxy_preserves_actual_identity_and_replaces_spoofed_x_forwarded_identity(self):
        spoofed = {"X-Forwarded-For": "198.51.100.66", "X-Forwarded-Proto": "https",
                   "X-Forwarded-Host": "attacker.invalid:444"}
        for incoming in ({}, spoofed):
            with self.subTest(spoofed=bool(incoming)):
                client_address, headers = self.forwarded_headers(incoming)
                self.assertEqual(headers["x-forwarded-for"], client_address)
                self.assertEqual(headers["x-forwarded-proto"], "http")
                self.assertEqual(headers["x-forwarded-host"], "campus.example:8080")
                self.assertEqual(headers["host"], "campus.example:8080")

    def test_untrusted_supplemental_forwarding_headers_do_not_reach_gateway(self):
        incoming = {"Forwarded": "for=198.51.100.66;proto=https;host=attacker.invalid:444",
                    "X-Forwarded-Prefix": "/attacker", "X-Forwarded-Port": "444",
                    "X-Forwarded-Ssl": "on"}
        _, headers = self.forwarded_headers(incoming)
        for name in incoming:
            with self.subTest(header=name):
                self.assertNotIn(name.lower(), headers)


if __name__ == "__main__":
    unittest.main(verbosity=2)
