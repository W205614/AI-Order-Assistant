"""Campus timeouts preserve committed tool evidence without repeating writes."""
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.agent.campus import CampusRequest, run
from app.agent.llm import LLMError
from app.budget import BudgetExceeded


def tool(name, identity):
    return SimpleNamespace(id=identity, function=SimpleNamespace(
        name=name, arguments='{"productType":"SKU","productId":1,"quantity":1,"expectedVersion":0}'))


class CampusPartialCompletionTest(unittest.TestCase):
    def setUp(self):
        self.req = CampusRequest(userId=1, campusId=2, fulfillmentMode="PICKUP",
                                 message="把选择的米饭加入购物车", jwtToken="offline-token",
                                 requestId="partial-offline", deadlineEpochMs=int((time.time() + 30) * 1000))
        context = patch("app.agent.campus._conversation", return_value=[])
        context.start()
        self.addCleanup(context.stop)

    def test_timeout_between_tools_preserves_success_and_stops_remaining_writes(self):
        response = SimpleNamespace(content="", tool_calls=[tool("prepare_campus_cart", "one"),
                                                           tool("prepare_campus_cart", "two")])
        with patch("app.agent.campus.chat_with_tools", return_value=response), \
                patch("app.agent.campus.remaining", side_effect=[30, 30, BudgetExceeded("agent_deadline")]), \
                patch("app.agent.campus.execute", return_value={"ok": True, "data": {"cartVersion": 1}}) as execute:
            result = run(self.req)
        self.assertEqual(1, execute.call_count)
        self.assertEqual("degraded", result["outcome"])
        self.assertEqual("agent_deadline", result["errorCategory"])
        self.assertEqual(1, len(result["toolCalls"]))
        self.assertEqual("ok", result["toolCalls"][0]["status"])
        self.assertEqual(1, result["toolCalls"][0]["item"]["quantity"])
        self.assertIn("不要重复加购", result["reply"])

    def test_model_wrapped_budget_exhaustion_after_write_is_degraded(self):
        response = SimpleNamespace(content="", tool_calls=[tool("prepare_campus_cart", "one")])
        for category in ("agent_deadline", "agent_token_budget"):
            with self.subTest(category=category), \
                    patch("app.agent.campus.chat_with_tools", side_effect=[response, LLMError(category)]), \
                    patch("app.agent.campus.execute", return_value={"ok": True, "data": {}}):
                result = run(self.req)
            self.assertEqual("degraded", result["outcome"])
            self.assertEqual(category, result["errorCategory"])
            self.assertEqual(1, len(result["toolCalls"]))

    def test_timeout_without_successful_operation_still_raises_for_http_504(self):
        for failure in (BudgetExceeded("agent_deadline"), LLMError("agent_deadline"),
                        LLMError("agent_token_budget")):
            with self.subTest(failure=type(failure).__name__), \
                    patch("app.agent.campus.chat_with_tools", side_effect=failure), \
                    self.assertRaises(BudgetExceeded):
                run(self.req)

    def test_failed_tool_does_not_turn_timeout_into_partial_success(self):
        response = SimpleNamespace(content="", tool_calls=[tool("prepare_campus_cart", "one")])
        with patch("app.agent.campus.chat_with_tools", side_effect=[response, LLMError("agent_deadline")]), \
                patch("app.agent.campus.execute", return_value={"ok": False, "errorCategory": "cart_version_conflict"}), \
                self.assertRaises(BudgetExceeded):
            run(self.req)

    def test_quote_survives_read_timeout_but_is_invalidated_after_cart_write(self):
        for wrote_cart in (False, True):
            pending = [tool("quote_campus_cart", "quote")]
            if wrote_cart:
                pending.append(tool("prepare_campus_cart", "write"))
            pending.append(tool("get_campus_cart", "never"))
            checks = [30] * len(pending) + [BudgetExceeded("agent_deadline")]
            results = [{"ok": True, "data": {"id": "current-quote"}}]
            if wrote_cart:
                results.append({"ok": True, "data": {}})
            with self.subTest(wrote_cart=wrote_cart), \
                    patch("app.agent.campus.chat_with_tools", return_value=SimpleNamespace(content="", tool_calls=pending)), \
                    patch("app.agent.campus.remaining", side_effect=checks), \
                    patch("app.agent.campus.execute", side_effect=results):
                result = run(self.req)
            self.assertEqual("degraded", result["outcome"])
            if wrote_cart:
                self.assertNotIn("quote", result)
            else:
                self.assertEqual("current-quote", result["quote"]["id"])


if __name__ == "__main__":
    unittest.main()
