"""Verify download authentication stays on the original HTTP request."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from pathlib import Path
import threading
import unittest
import urllib.request

spec = importlib.util.spec_from_file_location("publish_maven", Path(__file__).with_name("publish-maven.py"))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class MavenPublicationTest(unittest.TestCase):
    def test_redirect_does_not_forward_package_or_api_credentials(self):
        received = []

        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"artifact")

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Target) as target:
            class Origin(Target):
                def do_GET(self):
                    received.append(self.headers.get("Authorization"))
                    self.send_response(302)
                    self.send_header("Location", f"http://127.0.0.1:{target.server_port}/asset")
                    self.end_headers()

            with ThreadingHTTPServer(("127.0.0.1", 0), Origin) as origin:
                threads = [threading.Thread(target=server.serve_forever) for server in (origin, target)]
                for thread in threads:
                    thread.start()
                try:
                    for authorization in ("Bearer test-placeholder", "Basic test-placeholder"):
                        with self.subTest(authorization=authorization):
                            received.clear()
                            request = publication.authenticated_request(
                                f"http://127.0.0.1:{origin.server_port}/asset", authorization
                            )
                            with urllib.request.urlopen(request, timeout=5) as response:
                                self.assertEqual(b"artifact", response.read())
                            self.assertEqual([authorization, None], received)
                finally:
                    origin.shutdown()
                    target.shutdown()
                    for thread in threads:
                        thread.join()


if __name__ == "__main__":
    unittest.main()
