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
        return CampusRequest(
            userId=1,
            campusId=2,
            addressId=3,
            fulfillmentMode="DELIVERY",
            message="查询当前商品和报价",
            jwtToken="unit-test-token",
            requestId="unit-test-request",
            deadlineEpochMs=int((time.time() + 30) * 1000),
        )

    def result_view(self, name, data):
        calls = []
        tool = SimpleNamespace(id="test-tool", function=SimpleNamespace(name=name, arguments="{}"))

        def model(messages, tools):
            calls.append(deepcopy(messages))
            return SimpleNamespace(content="已查询", tool_calls=[tool] if len(calls) == 1 else [])

        with patch("app.agent.campus._conversation", return_value=[]), patch(
            "app.agent.campus.chat_with_tools", side_effect=model
        ), patch("app.agent.campus.execute", return_value={"ok": True, "data": data}):
            result = run(self.request())
        return result, json.loads(calls[-1][-1]["content"])["data"]

    def test_menu_and_nested_recommendations_describe_customer_restrictions(self):
        for name, original in [
            ("search_campus_menu", [{"id": 1, "name": "米饭", "safetySnapshot": "花生,鸡蛋"}]),
            (
                "recommend_campus_meal",
                {
                    "candidates": [
                        {
                            "shops": [
                                {
                                    "lines": [
                                        {
                                            "product": {"id": 1, "safetySnapshot": "花生,鸡蛋"},
                                            "quantity": 1,
                                        }
                                    ]
                                }
                            ]
                        }
                    ]
                },
            ),
        ]:
            with self.subTest(tool=name):
                before = deepcopy(original)
                _, view = self.result_view(name, original)
                product = (
                    view[0]
                    if isinstance(view, list)
                    else view["candidates"][0]["shops"][0]["lines"][0]["product"]
                )
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
        original = {
            "id": "real-quote",
            "total": 21.5,
            "unavailable": {},
            "shops": [
                {"merchantId": 1, "lines": [{"product": {"id": 1, "safetySnapshot": "花生"}}]}
            ],
        }
        before = deepcopy(original)
        response, view = self.result_view("quote_campus_cart", original)
        self.assertEqual(
            ["花生"], view["shops"][0]["lines"][0]["product"]["appliedCustomerAllergens"]
        )
        self.assertIs(original, response["quote"])
        self.assertEqual(before, response["quote"])
        self.assertEqual(
            "花生", response["quote"]["shops"][0]["lines"][0]["product"]["safetySnapshot"]
        )

    def test_recommendations_keep_price_and_safety_without_checkout_bookkeeping(self):
        product = {
            "type": "SKU", "id": 7, "merchantId": 2, "name": "米饭 / 标准份",
            "price": 12, "packagingFee": 1, "safetySnapshot": "花生",
            "version": 63, "dishId": 3, "components": [{"skuId": 7, "quantity": 1}],
        }
        shop = {
            "merchantId": 2, "merchantName": "饭堂", "merchantVersion": 1, "ruleVersion": 4,
            "couponId": 9, "goods": 12, "packaging": 1, "delivery": 3,
            "shopDiscount": 2, "platformDiscount": 1, "payable": 13,
            "etaMin": 20, "etaMax": 60,
            "lines": [{"cartId": 0, "cartVersion": 0, "product": product, "quantity": 1}],
        }
        original = {
            "message": "候选已通过报价核验", "searchLimited": True, "combinationsExamined": 2,
            "candidates": [{"id": "checkout-quote", "version": 1, "addressVersion": 1,
                            "request": {"addressId": 99}, "expiresAt": "2026-11-07T22:00:00",
                            "total": 13, "shops": [shop]}],
        }
        before = deepcopy(original)
        _, view = self.result_view("recommend_campus_meal", original)
        candidate = view["candidates"][0]
        self.assertEqual(13, candidate["total"])
        self.assertEqual("2026-11-07T22:00:00", candidate["expiresAt"])
        self.assertEqual(2, view["combinationsExamined"])
        selected = candidate["shops"][0]
        self.assertEqual(("饭堂", 9, 13), (selected["merchantName"], selected["couponId"], selected["payable"]))
        self.assertEqual(["花生"], selected["lines"][0]["product"]["appliedCustomerAllergens"])
        self.assertEqual(7, selected["lines"][0]["product"]["id"])
        self.assertNotIn("request", candidate)
        self.assertNotIn("merchantVersion", selected)
        self.assertNotIn("cartVersion", selected["lines"][0])
        self.assertNotIn("components", selected["lines"][0]["product"])
        self.assertEqual(before, original)
        self.assertLess(len(json.dumps(view)), len(json.dumps(original)))


if __name__ == "__main__":
    unittest.main()
