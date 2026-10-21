"""Java-completed prepare/quote tasks must not spend a fourth rephrasing request."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import redis
from app import budget
from app.agent.campus import CampusRequest, _quoted_cart_reply, run


class CampusQuoteCompletionTest(unittest.TestCase):
    def setUp(self):
        self.req = CampusRequest(userId=1, campusId=2, addressId=3, fulfillmentMode="DELIVERY",
            message="就按你刚才推荐的最便宜跨店组合，每样一份，帮我加入购物车，并用刚才查询到的店铺券和平台券报价；不要下单或支付。",
            jwtToken="fixture-token", requestId="fixture-request", deadlineEpochMs=int((time.time()+35)*1000))
        self.calls = [
            {"tool": "get_campus_cart", "status": "ok"},
            {"tool": "prepare_campus_cart", "status": "ok", "item": {"productType": "SKU", "productId": 1, "quantity": 1}},
            {"tool": "prepare_campus_cart", "status": "ok", "item": {"productType": "SKU", "productId": 2, "quantity": 1}},
            {"tool": "quote_campus_cart", "status": "ok"}]
        self.quote = {"id": "fixture-quote", "version": 1,
            "expiresAt": (datetime.now(timezone(timedelta(hours=8)))+timedelta(minutes=5)).replace(tzinfo=None).isoformat(),
            "request": {"campusId": 2, "addressId": 3, "fulfillmentMode": "DELIVERY", "merchantIds": [10, 20]},
            "total": 21.5, "unavailable": {}, "shops": []}
        for merchant, name, product, goods, packaging, delivery, discount, promotion, payable in (
                (10, "饭店", "米饭", 12, 1, 3, 2, 1.67, 12.33),
                (20, "茶店", "奶茶", 8, .5, 2, 0, 1.33, 9.17)):
            self.quote["shops"].append({"merchantId": merchant, "merchantName": name,
                "lines": [{"product": {"type": "SKU", "id": 1 if merchant == 10 else 2,
                            "merchantId": merchant, "name": product}, "quantity": 1}],
                "goods": goods, "packaging": packaging, "delivery": delivery,
                "shopDiscount": discount, "platformDiscount": promotion, "payable": payable})

    def test_three_model_calls_finish_before_unnecessary_fourth_would_exhaust_budget(self):
        def tool(name, args, index):
            return SimpleNamespace(id=str(index), function=SimpleNamespace(name=name, arguments=json.dumps(args)))
        rounds = [
            [tool("get_campus_cart", {}, 1)],
            [tool("prepare_campus_cart", item["item"], i+2) for i, item in enumerate(self.calls[1:3])],
            [tool("quote_campus_cart", {"merchantIds": [10, 20]}, 4)]]
        count = 0
        def model(*_):
            nonlocal count
            budget.reserve_tokens(15000)
            count += 1
            return SimpleNamespace(content="", tool_calls=rounds[count-1])
        def execute(req, name, raw):
            return {"ok": True, "data": self.quote if name == "quote_campus_cart" else []}
        before = deepcopy(self.quote)
        with patch.object(budget.settings, "rate_limit_backend", "memory"), \
                patch.object(budget.settings, "request_token_budget", 48000), \
                patch("app.agent.campus._conversation", return_value=[]) as history, \
                patch("app.agent.campus.chat_with_tools", side_effect=model), \
                patch("app.agent.campus.execute", side_effect=execute):
            with budget.admission(0, time.time()+35, campus_user_id=1):
                result = run(self.req)
                self.assertEqual(45000, budget.current.get().tokens)
                with self.assertRaises(budget.BudgetExceeded):
                    budget.reserve_tokens(15000)
        self.assertEqual(3, count)
        self.assertEqual("completed", result["outcome"])
        self.assertEqual("java_quote", result["completionSource"])
        self.assertNotIn("errorCategory", result)
        self.assertIs(self.quote, result["quote"])
        self.assertEqual(before, result["quote"])
        self.assertIn("合计 21.50 元", result["reply"])
        self.assertIn("尚未创建订单或模拟支付", result["reply"])
        self.assertIn("店铺优惠 2.00 元，平台优惠 1.67 元", result["reply"])
        self.assertEqual(result["reply"], history.call_args.args[1][1]["content"])

    def test_incomplete_stale_wrong_context_or_mismatched_quotes_cannot_complete(self):
        changes = [
            lambda q: q.update(unavailable={"10": "缺货"}),
            lambda q: q.update(expiresAt=(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()),
            lambda q: q["request"].update(campusId=9),
            lambda q: q["request"].update(addressId=9),
            lambda q: q["request"].update(fulfillmentMode="PICKUP"),
            lambda q: q["shops"].pop(),
            lambda q: q["shops"][0]["lines"][0].update(quantity=2),
            lambda q: q["shops"][0]["lines"][0]["product"].update(merchantId=20),
            lambda q: q.update(total=20),
            lambda q: q.update(total="NaN"),
            lambda q: q.pop("expiresAt"),
            lambda q: q["shops"][0].update(payable=0),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                quote = deepcopy(self.quote)
                change(quote)
                self.assertIsNone(_quoted_cart_reply(self.req, self.calls, quote))
        self.assertIsNone(_quoted_cart_reply(self.req, self.calls, None))
        self.assertIsNone(_quoted_cart_reply(self.req, self.calls + [{"tool": "search_campus_menu", "status": "error"}], self.quote))
        self.assertIsNone(_quoted_cart_reply(self.req, self.calls[-1:], self.quote))

    def test_extra_questions_negations_and_quotes_are_not_terminal_commands(self):
        for message in (
                "把刚才的组合加入购物车，每样一份，并报价，然后解释如何退款",
                "把刚才的组合加入购物车，并报价，再查询订单",
                "请问能否把奶茶加入购物车并报价", "不要加入购物车，只报价",
                "引用这句话：把奶茶加入购物车，并报价", "把奶茶加入购物车"):
            with self.subTest(message=message):
                self.assertIsNone(_quoted_cart_reply(self.req.model_copy(update={"message": message}), self.calls, self.quote))

    def test_history_failure_does_not_hide_real_completed_quote(self):
        from app.agent.campus import _completed_reply
        with patch("app.agent.campus._conversation", side_effect=redis.ConnectionError):
            result = _completed_reply(self.req, [], self.calls, self.quote, "已报价", "java_quote")
        self.assertEqual("completed", result["outcome"])
        self.assertFalse(result["contextSaved"])
        self.assertIn("近期对话暂未保存", result["reply"])

    def test_write_after_quote_invalidates_completion_until_requoted(self):
        tool = lambda name, args: SimpleNamespace(id=name, function=SimpleNamespace(name=name, arguments=json.dumps(args)))
        response = SimpleNamespace(content="", tool_calls=[
            tool("quote_campus_cart", {"merchantIds": [10, 20]}),
            tool("prepare_campus_cart", {"productType": "SKU", "productId": 1, "quantity": 2})])
        from app.agent.llm import LLMError
        with patch("app.agent.campus._conversation", return_value=[]), \
                patch("app.agent.campus.chat_with_tools", side_effect=[response, LLMError("agent_token_budget")]), \
                patch("app.agent.campus.execute", side_effect=[{"ok": True, "data": self.quote}, {"ok": True, "data": 1}]):
            result = run(self.req)
        self.assertEqual("degraded", result["outcome"])
        self.assertNotIn("completionSource", result)
        self.assertNotIn("quote", result)


if __name__ == "__main__":
    unittest.main()
