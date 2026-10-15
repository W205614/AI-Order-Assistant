import json
import time
import unittest
from unittest.mock import patch

from app.agent.campus import CampusRequest, execute, MODELS, run


class CampusToolsTest(unittest.TestCase):
    def request(self):
        return CampusRequest(userId=1, campusId=2, addressId=3, fulfillmentMode="DELIVERY",
                             message="看看奶茶", jwtToken="test-token", requestId="test-request",
                             deadlineEpochMs=int((time.time()+30)*1000))

    def test_transaction_delivery_and_refund_actions_are_not_exposed(self):
        for name in ("confirm_checkout", "pay", "cancel_order", "approve_refund", "assign_rider"):
            with patch("app.agent.campus.JavaClient") as client:
                self.assertFalse(execute(self.request(), name, "{}")["ok"])
                client.assert_not_called()
        self.assertEqual(7, len(MODELS))

    def test_model_cannot_override_campus_or_identity(self):
        for key in ("campusId", "userId", "jwtToken", "addressId", "merchantId"):
            with patch("app.agent.campus.JavaClient") as client:
                result=execute(self.request(), "search_campus_menu", json.dumps({"keyword":"奶茶", key:999}))
                self.assertFalse(result["ok"])
                client.assert_not_called()

    def test_recommendation_uses_java_context_and_exact_budget(self):
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.post.return_value={"candidates":[]}
            result=execute(self.request(), "recommend_campus_meal", '{"budget":"30.05","keywords":["饭","奶茶"]}')
            self.assertTrue(result["ok"])
            body=client.return_value.post.call_args.args[2]
            self.assertEqual(2,body["campusId"])
            self.assertEqual(3,body["addressId"])
            self.assertEqual("30.05",body["budget"])

    def test_cart_changes_require_valid_versions_and_quantity(self):
        for payload in ({"productType":"SKU","productId":1,"quantity":0},
                        {"productType":"SKU","productId":1,"quantity":1,"expectedVersion":-1},
                        {"productType":"RAW_SQL","productId":1,"quantity":1}):
            with patch("app.agent.campus.JavaClient") as client:
                self.assertFalse(execute(self.request(),"prepare_campus_cart",json.dumps(payload))["ok"])
                client.assert_not_called()

    def test_order_query_does_not_fetch_recipient_or_verification_code(self):
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.get.return_value=[]
            self.assertTrue(execute(self.request(),"query_campus_orders","{}")["ok"])
            self.assertEqual("/checkouts",client.return_value.get.call_args.args[0])

    def test_model_failure_preserves_manual_ordering_fallback(self):
        from app.agent.llm import LLMError
        with patch("app.agent.campus.chat_with_tools", side_effect=LLMError("model_unavailable")):
            result=run(self.request())
            self.assertEqual("degraded",result["outcome"])
            self.assertIn("菜单",result["reply"])


if __name__ == "__main__":
    unittest.main()
