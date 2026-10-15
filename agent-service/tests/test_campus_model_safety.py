"""Model-facing restrictions must never masquerade as food ingredient metadata."""
from copy import deepcopy
import json
from types import SimpleNamespace
import time
import unittest
from unittest.mock import patch

from app.agent.campus import CampusRequest, run


class CampusModelSafetyTest(unittest.TestCase):
    def request(self):
        return CampusRequest(userId=1, campusId=2, addressId=3, fulfillmentMode="DELIVERY",
                             message="查询当前商品和报价", jwtToken="unit-test-token",
                             requestId="unit-test-request", deadlineEpochMs=int((time.time()+30)*1000))

    def result_view(self, name, data):
        calls = []
        tool = SimpleNamespace(id="test-tool", function=SimpleNamespace(name=name, arguments="{}"))
        def model(messages, tools):
            calls.append(deepcopy(messages))
            return SimpleNamespace(content="已查询", tool_calls=[tool] if len(calls) == 1 else [])
        with patch("app.agent.campus._conversation", return_value=[]), \
             patch("app.agent.campus.chat_with_tools", side_effect=model), \
             patch("app.agent.campus.execute", return_value={"ok": True, "data": data}):
            result = run(self.request())
        return result, json.loads(calls[-1][-1]["content"])["data"]

    def test_menu_and_nested_recommendations_describe_customer_restrictions(self):
        for name, original in [
            ("search_campus_menu", [{"id": 1, "name": "米饭", "safetySnapshot": "花生,鸡蛋"}]),
            ("recommend_campus_meal", {"candidates": [{"shops": [{"lines": [
                {"product": {"id": 1, "safetySnapshot": "花生,鸡蛋"}, "quantity": 1}]}]}]}),
        ]:
            with self.subTest(tool=name):
                before = deepcopy(original)
                _, view = self.result_view(name, original)
                product = view[0] if isinstance(view, list) else view["candidates"][0]["shops"][0]["lines"][0]["product"]
                self.assertEqual(["花生", "鸡蛋"], product["appliedCustomerAllergens"])
                self.assertNotIn("safetySnapshot", product)
                self.assertEqual(before, original)

    def test_cart_snapshot_is_not_labeled_currently_safe(self):
        original = [{"product_id": 1, "safety_allergens": "花生", "version": 4}]
        _, view = self.result_view("get_campus_cart", original)
        self.assertEqual(["花生"], view[0]["appliedCustomerAllergens"])
        self.assertNotIn("safety_allergens", view[0])
        self.assertEqual({"product_id", "appliedCustomerAllergens", "version"}, set(view[0]))
        self.assertEqual("花生", original[0]["safety_allergens"])

    def test_quote_model_view_preserves_raw_browser_quote(self):
        original = {"id": "real-quote", "total": 21.5, "unavailable": {}, "shops": [
            {"merchantId": 1, "lines": [{"product": {"id": 1, "safetySnapshot": "花生"}}]}]}
        before = deepcopy(original)
        response, view = self.result_view("quote_campus_cart", original)
        self.assertEqual(["花生"], view["shops"][0]["lines"][0]["product"]["appliedCustomerAllergens"])
        self.assertIs(original, response["quote"])
        self.assertEqual(before, response["quote"])
        self.assertEqual("花生", response["quote"]["shops"][0]["lines"][0]["product"]["safetySnapshot"])


if __name__ == "__main__":
    unittest.main()
