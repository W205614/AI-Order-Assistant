import unittest
from unittest.mock import patch

import httpx

from app.gateway.java_client import JavaApiError, JavaClient


class JavaClientSecurityTest(unittest.TestCase):
    def test_route_cannot_replace_the_configured_origin(self):
        for path in ("@evil.invalid/cart", "https://evil.invalid/cart", "//evil.invalid/cart"):
            with self.subTest(path=path), patch("app.gateway.java_client._http_client") as transport:
                with self.assertRaises(ValueError):
                    JavaClient(base_url="http://gateway:9090").get(path)
                transport.assert_not_called()

    def test_route_preserves_the_base_path_and_query_without_changing_host(self):
        observed = []

        def respond(request):
            observed.append(request)
            return httpx.Response(200, json={"code": 1, "data": []})

        with httpx.Client(transport=httpx.MockTransport(respond)) as transport, patch(
            "app.gateway.java_client._http_client", return_value=transport
        ):
            self.assertEqual([], JavaClient(base_url="http://gateway:9090/api").get("/cart?version=7"))
        self.assertEqual("http://gateway:9090/api/cart?version=7", str(observed[0].url))

    def test_transport_details_do_not_enter_the_public_tool_error(self):
        for error, category in (
            (httpx.ReadTimeout("private-host credential=hidden"), "java_timeout"),
            (httpx.ConnectError("private-host credential=hidden"), "java_network_error"),
        ):
            with self.subTest(category=category), patch("app.gateway.java_client._http_client") as transport:
                transport.return_value.request.side_effect = error
                with self.assertRaises(JavaApiError) as caught:
                    JavaClient(base_url="http://gateway:9090").get("/cart")
                self.assertEqual(category, caught.exception.category)
                self.assertNotIn("private-host", str(caught.exception))
                self.assertNotIn("credential", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
