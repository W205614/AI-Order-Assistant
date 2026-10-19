"""Fault isolation for the bounded best-effort audit writer; no external services."""
import json
import tempfile
import threading
import unittest
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from app import metrics


class AsyncAuditTest(unittest.TestCase):
    def setUp(self):
        metrics.shutdown_writer()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        for name, value in (("_EVENTS", deque(maxlen=10000)), ("_TOTAL_CHATS", 0),
                            ("_TOTAL_FAILURES", 0), ("_AUDIT_WRITE_FAILURES", 0),
                            ("_AUDIT_QUEUE_DROPPED", 0), ("_WRITER", metrics._AuditWriter(1)),
                            ("_LOG_DIR", root), ("_LOG_FILE", root / "audit.jsonl"),
                            ("_BACKUP_FILE", root / "audit.1.jsonl")):
            self.stack.enter_context(patch.object(metrics, name, value))
        self.addCleanup(metrics.shutdown_writer)

    def test_slow_disk_does_not_block_record_and_full_queue_drops_newest(self):
        entered, release = threading.Event(), threading.Event()
        written = []

        def blocked_write(line):
            entered.set()
            release.wait(5)
            written.append(json.loads(line))

        with patch.object(metrics, "_write_line", side_effect=blocked_write), \
                ThreadPoolExecutor(max_workers=1) as requests:
            try:
                metrics.record({"traceId": "first", "success": True})
                self.assertTrue(entered.wait(1))
                requests.submit(metrics.record, {"traceId": "queued", "success": True}).result(timeout=1)
                requests.submit(metrics.record, {"traceId": "dropped", "success": True}).result(timeout=1)
                values = metrics.stats()
                self.assertEqual(3, values["totalChats"])
                self.assertEqual(1, values["auditQueueDropped"])
                self.assertEqual(0, values["auditWriteFailures"])
            finally:
                release.set()
                metrics._WRITER.pending.join()
        self.assertEqual(["first", "queued"], [item["traceId"] for item in written])

    def test_queued_payload_is_immutable_and_redacted(self):
        entered, release = threading.Event(), threading.Event()
        original_write = metrics._write_line

        def blocked_write(line):
            entered.set()
            release.wait(5)
            original_write(line)

        payload = {"success": True, "toolEvents": [{"tool": "get_campus_cart", "status": "ok"}],
                   "prompt": "private customer text", "jwtToken": "secret"}
        with patch.object(metrics, "_write_line", side_effect=blocked_write):
            try:
                metrics.record(payload)
                self.assertTrue(entered.wait(1))
                payload["toolEvents"][0]["status"] = "changed"
            finally:
                release.set()
                metrics._WRITER.pending.join()
        logged = metrics._LOG_FILE.read_text(encoding="utf-8")
        self.assertNotIn("private", logged)
        self.assertNotIn("secret", logged)
        self.assertEqual("ok", json.loads(logged)["toolEvents"][0]["status"])

    def test_shutdown_has_bounded_join_even_if_disk_is_stuck(self):
        entered, release = threading.Event(), threading.Event()

        def blocked_write(line):
            entered.set()
            release.wait(5)

        with patch.object(metrics, "_write_line", side_effect=blocked_write):
            try:
                metrics.record({"success": True})
                self.assertTrue(entered.wait(1))
                with patch.object(metrics._WRITER._thread, "join") as join:
                    metrics.shutdown_writer()
                join.assert_called_once_with(timeout=2.0)
                self.assertTrue(metrics._WRITER._thread.is_alive())
                stopped_writer = metrics._WRITER
                metrics.start_writer()
                self.assertIs(stopped_writer, metrics._WRITER)
                metrics.record({"success": True})
                self.assertEqual(1, metrics.stats()["auditQueueDropped"])
            finally:
                release.set()
                metrics._WRITER.pending.join()

    def test_writer_recovers_after_io_failure_and_shutdown_drains_pending_line(self):
        with patch.object(metrics, "_write_line", side_effect=OSError("disk unavailable")):
            metrics.record({"traceId": "failed", "success": True})
            metrics._WRITER.pending.join()
        self.assertEqual(1, metrics.stats()["auditWriteFailures"])
        metrics.record({"traceId": "recovered", "success": True})
        metrics.shutdown_writer()
        self.assertFalse(metrics._WRITER._thread.is_alive())
        self.assertEqual("recovered", json.loads(metrics._LOG_FILE.read_text(encoding="utf-8"))["traceId"])
        self.assertEqual(2, metrics.stats()["totalChats"])

    def test_concurrent_lifespan_restart_reuses_capacity_and_lifetime_counters(self):
        metrics.start_writer()
        metrics.record({"traceId": "before-restart", "success": True})
        metrics.shutdown_writer()
        stopped_writer = metrics._WRITER
        with patch.object(metrics, "_AuditWriter", wraps=metrics._AuditWriter) as factory, \
                ThreadPoolExecutor(max_workers=4) as lifespans:
            futures = [lifespans.submit(metrics.start_writer) for _ in range(12)]
            for future in futures:
                future.result(timeout=1)
        factory.assert_called_once_with(1)
        self.assertIsNot(stopped_writer, metrics._WRITER)
        self.assertEqual(1, metrics._WRITER.pending.maxsize)
        self.assertEqual(1, metrics.stats()["totalChats"])
        metrics.record({"traceId": "after-restart", "success": True})
        metrics.shutdown_writer()
        logged = [json.loads(line) for line in metrics._LOG_FILE.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["before-restart", "after-restart"], [entry["traceId"] for entry in logged])
        self.assertEqual(2, metrics.stats()["totalChats"])
        self.assertEqual(0, metrics.stats()["auditQueueDropped"])


if __name__ == "__main__":
    unittest.main()
