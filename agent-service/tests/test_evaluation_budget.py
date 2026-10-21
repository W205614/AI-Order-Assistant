"""Paid-evaluation ceilings fail closed before any provider network operation."""
import os
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

import redis
from app import evaluation
from app.agent import llm
from app.budget import CapacityExceeded


class EvaluationBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(evaluation.settings, "evaluation_run_id", "fixture"))
        self.client = Mock()
        self.stack.enter_context(patch("app.main._get_redis_client", return_value=self.client))

    def test_disabled_evaluation_does_not_need_redis(self):
        with patch.object(evaluation.settings, "evaluation_run_id", ""):
            evaluation.charge_provider_attempt(100)
            self.assertIsNone(evaluation.status())
        self.client.eval.assert_not_called()

    def test_charge_is_pre_dispatch_and_every_provider_retry_is_counted(self):
        self.client.eval.return_value = 1
        provider = Mock()
        provider.chat.completions.create.side_effect = [ConnectionError("private error"),
            SimpleNamespace(choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content="ok"))])]
        with patch.object(llm.settings, "llm_max_retries", 1), patch("app.agent.llm._client", return_value=provider), \
                patch("app.agent.llm.reserve_tokens"), patch("app.agent.llm.time.sleep"):
            self.assertEqual("ok", llm.chat_with_tools([{"role": "user", "content": "test"}]).content)
        self.assertEqual(2, self.client.eval.call_count)
        self.assertEqual(2, provider.chat.completions.create.call_count)
        for call in self.client.eval.call_args_list:
            self.assertEqual("ai-order-agent:evaluation:fixture", call.args[2])
            self.assertGreater(call.args[3], 0)
            self.assertEqual((60, 150000), call.args[4:])

    def test_exhaustion_and_uncertain_redis_ack_do_not_dispatch(self):
        for result in (0, redis.ConnectionError("private details")):
            self.client.eval.side_effect = result if isinstance(result, Exception) else None
            self.client.eval.return_value = result
            provider = Mock()
            with patch("app.agent.llm._client", return_value=provider), patch("app.agent.llm.reserve_tokens"):
                with self.assertRaises(llm.LLMError) as raised:
                    llm.chat_with_tools([{"role": "user", "content": "test"}])
            self.assertIn(raised.exception.category, {"evaluation_budget_exhausted", "evaluation_ledger_unavailable"})
            provider.chat.completions.create.assert_not_called()

    def test_status_is_redacted_and_independent_of_daily_rate_prefix(self):
        self.client.hgetall.return_value = {"calls": "4", "budgetUnits": "140000", "maxCalls": "60", "maxBudgetUnits": "150000"}
        value = evaluation.status()
        self.assertEqual(4, value["providerAttempts"])
        self.assertEqual(140000, value["budgetUnits"])
        with patch.object(evaluation.settings, "rate_limit_key_prefix", "changed-after-restart"):
            self.assertEqual("ai-order-agent:evaluation:fixture", evaluation.ledger_key())


@unittest.skipUnless(os.getenv("AGENT_TEST_REDIS_URL"), "requires isolated disposable Redis")
class EvaluationRedisTest(unittest.TestCase):
    def test_atomic_cap_survives_new_client_and_limits_cannot_be_enlarged(self):
        client = redis.Redis.from_url(os.environ["AGENT_TEST_REDIS_URL"], decode_responses=True)
        self.addCleanup(client.close)
        key = "evaluation-test:" + uuid.uuid4().hex
        def charge(_):
            return client.eval(evaluation._CHARGE, 1, key, 100, 3, 250)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(charge, range(12)))
        self.assertEqual(2, sum(results))
        self.assertEqual({"calls": "2", "budgetUnits": "200", "maxCalls": "3", "maxBudgetUnits": "250"}, client.hgetall(key))
        self.assertEqual(-1, client.ttl(key), "No expiry may reset repair/retest spend")
        other = redis.Redis.from_url(os.environ["AGENT_TEST_REDIS_URL"], decode_responses=True)
        self.addCleanup(other.close)
        self.assertEqual(0, other.eval(evaluation._CHARGE, 1, key, 51, 60, 150000))
        self.assertEqual(1, other.eval(evaluation._CHARGE, 1, key, 50, 60, 150000))
        self.assertEqual(0, other.eval(evaluation._CHARGE, 1, key, 1, 60, 150000))


if __name__ == "__main__":
    unittest.main()
