"""Offline fault and boundary regression; real Redis is opt-in and disposable."""
import os
import time
import tempfile
import unittest
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import redis
from fastapi import HTTPException

from app import budget, main, metrics
from app.agent.campus import CampusRequest, run
from app.schemas import ChatRequest, ChatResponse


class AccountingTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for target, name, value in (
            (budget, "_quotas", {}), (budget, "_active", {}), (budget, "_total", 0),
            (budget, "_settlement_failures", 0),
            (budget.settings, "rate_limit_backend", "memory"),
            (budget.settings, "request_token_budget", 48000),
            (budget.settings, "merchant_daily_tokens", 2000000),
            (budget.settings, "campus_daily_tokens", 2000000),
        ):
            self.stack.enter_context(patch.object(target, name, value))

    def test_unused_reservation_is_returned_and_42nd_round_is_available(self):
        for _ in range(42):
            with budget.admission(0, time.time() + 35, campus_user_id=1):
                budget.reserve_tokens(1000)
                self.assertGreaterEqual(sum(budget._quotas.values()), 48000)
        self.assertEqual(42000, sum(budget._quotas.values()))
        self.assertEqual(0, budget.gauges()["active"])

    def test_failure_keeps_dispatched_budget_and_releases_capacity(self):
        with self.assertRaisesRegex(RuntimeError, "provider timeout"):
            with budget.admission(5, time.time() + 35):
                budget.reserve_tokens(1500)
                raise RuntimeError("provider timeout")
        self.assertEqual(1500, sum(budget._quotas.values()))
        self.assertEqual(0, budget.gauges()["active"])

    def test_settlement_keeps_reservation_day_across_utc_midnight(self):
        with patch("app.budget.time.time", return_value=172799.0) as clock:
            with budget.admission(5, 172830):
                budget.reserve_tokens(1000)
                clock.return_value = 172801.0
        self.assertEqual({(5, 1): 1000}, budget._quotas)

    def test_campus_daily_limit_is_independent_from_merchant_limit(self):
        with patch.object(budget.settings, "merchant_daily_tokens", 1000):
            with self.assertRaises(budget.CapacityExceeded):
                with budget.admission(5, time.time() + 35):
                    budget.reserve_tokens(1000)
            with budget.admission(0, time.time() + 35, campus_user_id=1):
                budget.reserve_tokens(1000)
        self.assertEqual(1000, budget._quotas[(0, int(time.time() // 86400))])

    def test_campus_is_limited_per_user_and_still_has_global_limit(self):
        with patch.object(budget.settings, "max_concurrent", 3), \
                patch.object(budget.settings, "campus_user_concurrent", 2):
            with budget.admission(0, time.time() + 35, campus_user_id=1), \
                    budget.admission(0, time.time() + 35, campus_user_id=1):
                with self.assertRaises(budget.CapacityExceeded):
                    with budget.admission(0, time.time() + 35, campus_user_id=1):
                        pass
                with budget.admission(0, time.time() + 35, campus_user_id=2):
                    with self.assertRaises(budget.CapacityExceeded):
                        with budget.admission(0, time.time() + 35, campus_user_id=3):
                            pass
        self.assertEqual(0, budget.gauges()["active"])

    def test_redis_settlement_failure_retains_charge_without_hiding_business_result(self):
        client = Mock()
        client.eval.side_effect = [1, redis.ConnectionError("offline")]
        with patch.object(budget.settings, "rate_limit_backend", "redis"), \
                patch("app.main._get_redis_client", return_value=client):
            with budget.admission(5, time.time() + 35):
                budget.reserve_tokens(1000)
                result = "business-completed"
        self.assertEqual("business-completed", result)
        self.assertEqual(1, budget.gauges()["quotaSettlementFailures"])
        self.assertEqual(0, budget.gauges()["active"])
        reserve, settle = client.eval.call_args_list
        self.assertEqual(reserve.args[2:4], settle.args[2:4])
        self.assertEqual(1000, settle.args[4])

    def test_quota_backend_failure_is_fail_closed_before_provider_dispatch(self):
        client = Mock()
        client.eval.side_effect = redis.ConnectionError("offline")
        with patch.object(budget.settings, "rate_limit_backend", "redis"), \
                patch("app.main._get_redis_client", return_value=client):
            with self.assertRaisesRegex(budget.CapacityExceeded, "quota_backend_unavailable"):
                with budget.admission(0, time.time() + 35, campus_user_id=1):
                    budget.reserve_tokens(1000)
        client.eval.assert_called_once()
        self.assertEqual(0, budget.gauges()["active"])


class UnifiedMetricsTest(unittest.TestCase):
    def setUp(self):
        metrics.shutdown_writer()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        for name, value in (("_EVENTS", deque(maxlen=10000)), ("_TOTAL_CHATS", 0),
                            ("_TOTAL_FAILURES", 0), ("_AUDIT_WRITE_FAILURES", 0),
                            ("_AUDIT_QUEUE_DROPPED", 0), ("_WRITER", metrics._AuditWriter(1000)),
                            ("_LOG_DIR", root), ("_LOG_FILE", root / "audit.jsonl"),
                            ("_BACKUP_FILE", root / "audit.1.jsonl")):
            self.stack.enter_context(patch.object(metrics, name, value))
        self.stack.enter_context(patch.object(main.settings, "internal_api_key", "key"))
        self.stack.enter_context(patch.object(main.settings, "rate_limit_backend", "memory"))
        self.stack.enter_context(patch("app.main._apply_rate_limit"))
        self.addCleanup(metrics.shutdown_writer)

    def request(self, campus=False):
        common = dict(userId=1, message="查看菜单", requestId="offline-test",
                      deadlineEpochMs=int((time.time() + 35) * 1000))
        if campus:
            return CampusRequest(**common, campusId=1, fulfillmentMode="PICKUP", jwtToken="token")
        return ChatRequest(**common, merchantId=1)

    def invoke(self, req, campus=False):
        method = main.campus_chat if campus else main.chat
        return method(req, "key", "1", "0" if campus else "1", str(req.deadlineEpochMs))

    def test_both_entrypoints_count_once_and_preserve_campus_failures(self):
        req = self.request()
        with patch("app.main._chat", return_value=ChatResponse(traceId=req.requestId, reply="菜单")):
            self.invoke(req)
        campus = {"traceId": req.requestId, "reply": "查询失败", "outcome": "degraded",
                  "toolCalls": [{"tool": "get_campus_cart", "status": "error", "errorCategory": "java_timeout"}],
                  "errorCategory": "java_timeout"}
        with patch("app.main.run_campus", return_value=campus):
            self.invoke(self.request(True), True)
        result = metrics.stats()
        self.assertEqual(2, result["totalChats"])
        self.assertEqual(1, result["totalFailures"])
        self.assertEqual({"agent": 1, "campus": 1}, result["routingCounts"])
        self.assertEqual(1, result["toolFailureCount"])

    def test_capacity_deadline_and_unexpected_failures_are_counted_once(self):
        for campus in (False, True):
            for failure, code in ((budget.CapacityExceeded("agent_capacity"), 429),
                                  (budget.BudgetExceeded("agent_deadline"), 504),
                                  (RuntimeError("unexpected"), None)):
                with patch("app.main.admission", side_effect=failure):
                    if code is None:
                        with self.assertRaises(RuntimeError):
                            self.invoke(self.request(campus), campus)
                    else:
                        with self.assertRaises(HTTPException) as raised:
                            self.invoke(self.request(campus), campus)
                        self.assertEqual(code, raised.exception.status_code)
        self.assertEqual(6, metrics.stats()["totalChats"])
        self.assertEqual(6, metrics.stats()["totalFailures"])

    def test_log_failure_keeps_business_response_and_scrape_does_not_touch_disk(self):
        req = self.request()
        with patch("app.main._chat", return_value=ChatResponse(traceId=req.requestId, reply="草稿已生成")), \
                patch.object(Path, "open", side_effect=OSError("disk full")):
            result = self.invoke(req)
            metrics._WRITER.pending.join()
            values = metrics.stats()
            text = main.prometheus("key")
        self.assertEqual("草稿已生成", result.reply)
        self.assertEqual(1, values["totalChats"])
        self.assertEqual(1, values["auditWriteFailures"])
        self.assertIn("agent_audit_write_failures_total 1", text)

    def test_campus_tool_error_cannot_return_completed(self):
        tool = SimpleNamespace(id="t1", function=SimpleNamespace(name="get_campus_cart", arguments="{}"))
        responses = [SimpleNamespace(content="", tool_calls=[tool]),
                     SimpleNamespace(content="查询失败", tool_calls=[])]
        with patch("app.agent.campus._conversation", return_value=[]), \
                patch("app.agent.campus.chat_with_tools", side_effect=responses), \
                patch("app.agent.campus.execute", return_value={"ok": False, "errorCategory": "java_timeout"}):
            result = run(self.request(True))
        self.assertEqual("degraded", result["outcome"])
        self.assertEqual("java_timeout", result["errorCategory"])


@unittest.skipUnless(os.getenv("AGENT_TEST_REDIS_URL"), "requires an isolated disposable Redis")
class RedisAccountingIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.client = redis.Redis.from_url(os.environ["AGENT_TEST_REDIS_URL"], decode_responses=True)
        self.prefix = "audit-test-" + str(time.time_ns())
        self.addCleanup(self.client.close)

    def test_existing_balance_and_original_utc_bucket_survive_upgrade_and_midnight(self):
        quota = f"{self.prefix}:tokens:0:1"
        self.client.set(quota, 40000, ex=172800)
        with patch.object(budget.settings, "rate_limit_backend", "redis"), \
                patch.object(budget.settings, "rate_limit_key_prefix", self.prefix), \
                patch.object(budget.settings, "request_token_budget", 48000), \
                patch.object(budget.settings, "campus_daily_tokens", 2000000), \
                patch("app.main._get_redis_client", return_value=self.client), \
                patch("app.budget.time.time", return_value=172799.0) as clock:
            with budget.admission(0, 172830, campus_user_id=1):
                budget.reserve_tokens(1000)
                self.assertEqual("88000", self.client.get(quota))
                clock.return_value = 172801.0
        self.assertEqual("41000", self.client.get(quota))
        self.assertIsNone(self.client.get(f"{self.prefix}:tokens:0:2"))
        self.assertEqual([quota], list(self.client.scan_iter(self.prefix + "*")))

    def test_atomic_reservation_cap_and_idempotent_same_bucket_settlement(self):
        quota = self.prefix + ":{0:1}"
        def reserve(index):
            receipt = quota + ":" + str(index)
            return receipt, self.client.eval(budget._RESERVE_QUOTA, 2, quota, receipt, 100, 500)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(reserve, range(12)))
        winners = [receipt for receipt, allowed in results if allowed == 1]
        self.assertEqual(5, len(winners))
        self.assertEqual("500", self.client.get(quota))
        for receipt in winners:
            self.assertEqual(1, self.client.eval(budget._SETTLE_QUOTA, 2, quota, receipt, 20))
            self.assertEqual(0, self.client.eval(budget._SETTLE_QUOTA, 2, quota, receipt, 20))
        self.assertEqual("100", self.client.get(quota))
        self.assertGreater(self.client.ttl(quota), 0)
        self.assertEqual([], [k for k in self.client.scan_iter(self.prefix + "*") if k != quota])


if __name__ == "__main__":
    unittest.main()
