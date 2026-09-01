import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi import HTTPException

from app import main, metrics


class RateLimiterTest(unittest.TestCase):
    def setUp(self):
        main._rate_windows.clear()
        main._rate_checks = 0
        main._redis_client = None
        main._redis_client_url = ""

    def test_limits_each_authenticated_user(self):
        with patch.object(main.settings, "internal_api_key", "i" * 32), \
                patch.object(main.settings, "rate_limit_per_minute", 2), \
                patch.object(main.settings, "rate_limit_backend", "memory"), \
                patch.object(main.time, "monotonic", side_effect=[1.0, 2.0, 3.0]):
            main._verify_internal_request("i" * 32, "7")
            main._verify_internal_request("i" * 32, "7")
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("i" * 32, "7")
        self.assertEqual(429, raised.exception.status_code)

    def test_redis_backend_uses_one_atomic_window_per_authenticated_user(self):
        client = Mock()
        client.eval.side_effect = [1, 1, 0]
        with patch.object(main.settings, "internal_api_key", "i" * 32), \
                patch.object(main.settings, "rate_limit_per_minute", 2), \
                patch.object(main.settings, "rate_limit_backend", "redis"), \
                patch.object(main.settings, "rate_limit_key_prefix", "test:rate"), \
                patch("app.main._get_redis_client", return_value=client), \
                patch("app.main.metrics_record") as record:
            main._verify_internal_request("i" * 32, "7", "trace-rate-1")
            main._verify_internal_request("i" * 32, "8", "trace-rate-2")
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("i" * 32, "7", "trace-rate-3")
        self.assertEqual(429, raised.exception.status_code)
        self.assertEqual({}, main._rate_windows)
        self.assertIn("test:rate:user:7", client.eval.call_args_list[0].args)
        self.assertIn("test:rate:user:8", client.eval.call_args_list[1].args)
        self.assertEqual("agent_rate_limited", record.call_args.args[0]["errorCategory"])

    def test_redis_outage_is_fail_closed_and_audited_without_user_data(self):
        client = Mock()
        client.eval.side_effect = main.redis.ConnectionError("offline")
        with patch.object(main.settings, "internal_api_key", "i" * 32), \
                patch.object(main.settings, "rate_limit_backend", "redis"), \
                patch("app.main._get_redis_client", return_value=client), \
                patch("app.main.metrics_record") as record:
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("i" * 32, "7", "trace-rate-outage")
        self.assertEqual(503, raised.exception.status_code)
        self.assertEqual("服务暂时不可用，请稍后重试", raised.exception.detail)
        self.assertEqual({}, main._rate_windows)
        self.assertEqual("rate_limit_backend_unavailable", record.call_args.args[0]["errorCategory"])
        self.assertNotIn("7", str(record.call_args.args[0]))

    def test_rejects_invalid_gateway_identity_before_rate_state(self):
        with patch.object(main.settings, "internal_api_key", "i" * 32):
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_request("wrong", "7")
        self.assertEqual(401, raised.exception.status_code)
        self.assertEqual({}, main._rate_windows)

    def test_stats_requires_internal_key(self):
        with patch.object(main.settings, "internal_api_key", "i" * 32):
            with self.assertRaises(HTTPException) as raised:
                main._verify_internal_access("wrong")
            main._verify_internal_access("i" * 32)
        self.assertEqual(401, raised.exception.status_code)


class MetricsRotationTest(unittest.TestCase):
    def test_rotates_and_aggregates_current_and_backup_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(metrics, "_LOG_DIR", root), \
                    patch.object(metrics, "_LOG_FILE", root / "chat_log.jsonl"), \
                    patch.object(metrics, "_BACKUP_FILE", root / "chat_log.1.jsonl"), \
                    patch.object(metrics, "_MAX_BYTES", 100):
                entry = {"traceId": "trace-safe-1", "model": "test-model", "rounds": 2, "routing": "cart_router",
                         "toolCalls": 1, "toolOk": 1, "toolEvents": [{"tool": "list_menu", "status": "ok"}],
                         "stageTimings": [
                             {"stage": "faq_retrieval", "latencyMs": 1.25, "query": "must-not-be-stored"},
                             {"stage": "user:message", "latencyMs": 2},
                         ],
                         "latencyMs": 25, "success": True, "prompt": "must-not-be-stored"}
                metrics.record(entry)
                metrics.record(entry)
                result = metrics.stats()
                self.assertTrue((root / "chat_log.1.jsonl").exists())
                self.assertEqual(2, result["totalChats"])
                self.assertEqual(100.0, result["toolSuccessRate"])
                self.assertEqual(25.0, result["latencyP50Ms"])
                self.assertEqual(25.0, result["latencyP95Ms"])
                self.assertEqual(2, result["stageLatencyMs"]["faq_retrieval"]["count"])
                self.assertEqual(1.2, result["stageLatencyMs"]["faq_retrieval"]["p95"])
                self.assertEqual({"cart_router": 2}, result["routingCounts"])
                logged = (root / "chat_log.jsonl").read_text(encoding="utf-8")
                self.assertNotIn("must-not-be-stored", logged)

    def test_percentiles_and_error_categories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            current = root / "chat_log.jsonl"
            current.write_text("\n".join([
                json.dumps({"rounds": 1, "toolCalls": 0, "toolOk": 0, "latencyMs": 10, "success": True}),
                json.dumps({"rounds": 1, "toolCalls": 0, "toolOk": 0, "latencyMs": 40, "success": False, "errorCategory": "model_timeout"}),
            ]) + "\n", encoding="utf-8")
            with patch.object(metrics, "_LOG_FILE", current), \
                    patch.object(metrics, "_BACKUP_FILE", root / "missing.jsonl"):
                result = metrics.stats()
        self.assertEqual(10.0, result["latencyP50Ms"])
        self.assertEqual(40.0, result["latencyP95Ms"])
        self.assertEqual({"model_timeout": 1}, result["errorsByCategory"])

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
