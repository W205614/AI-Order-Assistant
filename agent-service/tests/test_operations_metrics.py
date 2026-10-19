import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from app.operations import prometheus_operations


class OperationsMetricsTest(unittest.TestCase):
    def test_unconfigured_does_not_report_an_unrelated_backup_failure(self):
        with patch.dict(os.environ, {"BACKUP_HEALTH_DIRECTORY": ""}):
            self.assertEqual("", prometheus_operations())

    def test_missing_corrupt_and_future_metadata_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ, {"BACKUP_HEALTH_DIRECTORY": directory}
        ):
            for content in (None, "invalid", "ai_order_backup_last_success_timestamp_seconds nan\n"
                            "ai_order_backup_last_attempt_success 1\n",
                            f"ai_order_backup_last_success_timestamp_seconds {time.time()+3600}\n"
                            "ai_order_backup_last_attempt_success 1\n"):
                if content is not None:
                    (Path(directory) / "backup.prom").write_text(content, encoding="utf-8")
                self.assertIn("ai_order_backup_status_available 0\n", prometheus_operations())

    def test_valid_status_is_exposed_without_reading_dump_files(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ, {"BACKUP_HEALTH_DIRECTORY": directory}
        ):
            (Path(directory) / "backup.prom").write_text(
                "ai_order_backup_last_success_timestamp_seconds 1700000000\n"
                "ai_order_backup_last_attempt_success 1\n", encoding="utf-8")
            result = prometheus_operations()
            self.assertIn("ai_order_backup_status_available 1\n", result)
            self.assertIn("ai_order_backup_last_success_timestamp_seconds 1.7e+09\n", result)
            self.assertIn("ai_order_backup_last_attempt_success 1\n", result)


if __name__ == "__main__":
    unittest.main()
