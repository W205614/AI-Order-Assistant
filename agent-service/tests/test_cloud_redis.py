import types
import unittest
from unittest.mock import patch

import redis

from app.config import Settings
from app import main


class CloudRedisTest(unittest.TestCase):
    def test_cloud_configuration_refuses_an_unencrypted_redis_url(self):
        with self.assertRaisesRegex(ValueError, "rediss"):
            Settings(rate_limit_backend="redis", redis_url="redis://cloud.example:6379/0",
                     redis_ssl_required=True)

    def test_actual_redis_client_requires_certificate_and_hostname_verification(self):
        settings = types.SimpleNamespace(redis_url="rediss://cloud.example:6380/0",
                                         redis_username="agent", redis_password="test-only",
                                         redis_ssl_ca_certs="/run/cloud-certs/redis.pem")
        with patch.object(main, "settings", settings), patch.object(main, "_redis_client", None), \
                patch.object(main, "_redis_client_url", None):
            client = main._get_redis_client()
            self.assertIs(client.connection_pool.connection_class, redis.SSLConnection)
            options = client.connection_pool.connection_kwargs
            self.assertEqual(options["ssl_cert_reqs"], "required")
            self.assertTrue(options["ssl_check_hostname"])
            self.assertEqual(options["ssl_ca_certs"], "/run/cloud-certs/redis.pem")
            client.close()


if __name__ == "__main__":
    unittest.main()
