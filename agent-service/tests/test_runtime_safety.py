import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException

from app import main, metrics


class RateLimiterTest(unittest.TestCase):
    def setUp(self):
        main._rate_windows.clear()
        main._rate_checks = 0

    def test_limits_each_authenticated_user(self):
        with patch.object(main.settings, "internal_api_key", "i" * 32), \
                patch.object(main.settings, "rate_limit_per_minute", 2), \
                patch.object(main.time, "monotonic", side_effect=[1.0, 2.0, 3.0]):
            main._verify_internal_request("i" * 32, "7")
            main._verify_internal_request("i" * 32, "7")
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("i" * 32, "7")
        self.assertEqual(429, raised.exception.status_code)

    def test_rejects_invalid_gateway_identity_before_rate_state(self):
        with patch.object(main.settings, "internal_api_key", "i" * 32):
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("wrong", "7")
        self.assertEqual(401, raised.exception.status_code)
        self.assertEqual({}, main._rate_windows)


class MetricsRotationTest(unittest.TestCase):
    def test_rotates_and_aggregates_current_and_backup_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(metrics, "_LOG_DIR", root), \
                    patch.object(metrics, "_LOG_FILE", root / "chat_log.jsonl"), \
                    patch.object(metrics, "_BACKUP_FILE", root / "chat_log.1.jsonl"), \
                    patch.object(metrics, "_MAX_BYTES", 100):
                entry = {"rounds": 2, "toolCalls": 1, "toolOk": 1,
                         "latencyMs": 25, "success": True, "padding": "x" * 30}
                metrics.record(entry)
                metrics.record(entry)
                result = metrics.stats()
                self.assertTrue((root / "chat_log.1.jsonl").exists())
                self.assertEqual(2, result["totalChats"])
                self.assertEqual(100.0, result["toolSuccessRate"])

    def test_skips_corrupt_metric_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            current = root / "chat_log.jsonl"
            current.write_text('not-json\n' + json.dumps({"rounds": "bad"}) + '\n', encoding="utf-8")
            with patch.object(metrics, "_LOG_FILE", current), \
                    patch.object(metrics, "_BACKUP_FILE", root / "missing.jsonl"):
                self.assertEqual(0, metrics.stats()["totalChats"])


if __name__ == "__main__":
    unittest.main()
