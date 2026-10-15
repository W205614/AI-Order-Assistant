import json
import time
import unittest
from unittest.mock import patch

from app.agent.campus import CampusRequest, execute, MODELS, run, _history, _conversation
from types import SimpleNamespace


class CampusToolsTest(unittest.TestCase):
    def setUp(self):
        _history.clear()

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
            self.assertEqual("model_unavailable",result["errorCategory"])

    def test_cart_write_requires_current_turn_explicit_authorization(self):
        payload='{"productType":"SKU","productId":1,"quantity":1}'
        for message in ("推荐奶茶", "只查询购物车", "帮我看看奶茶，别加入购物车", "不要帮我点餐或添加购物车",
                        "没让你加入购物车，只查询优惠", "没有让你加购", "不加入购物车", "请不要帮我点，先查询优惠", "帮我点评一下菜单", "解释加入购物车按钮"):
            request=self.request().model_copy(update={"message":message})
            with patch("app.agent.campus.JavaClient") as client:
                self.assertFalse(execute(request,"prepare_campus_cart",payload)["ok"])
                client.return_value.post.assert_not_called()

    def test_cart_preparation_uses_server_context_and_scoped_java_adapter(self):
        request=self.request().model_copy(update={"message":"就按刚才推荐加入购物车，不要下单支付"})
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.post.return_value=42
            self.assertTrue(execute(request,"prepare_campus_cart",'{"productType":"SKU","productId":1,"quantity":1}')["ok"])
            path,token,body=client.return_value.post.call_args.args
            self.assertEqual("/campus-assistant/prepare-cart",path)
            self.assertEqual(2,body["campusId"])
            self.assertEqual(3,body["addressId"])
            self.assertEqual(1,body["item"]["productId"])

    def test_unrelated_payment_negation_does_not_block_explicit_cart_authorization(self):
        request=self.request().model_copy(update={"message":"不要下单支付，只加入购物车"})
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.post.return_value=42
            self.assertTrue(execute(request,"prepare_campus_cart",'{"productType":"SKU","productId":1,"quantity":1}')["ok"])

    def test_cart_version_conflict_is_reported_without_retry_or_overwrite(self):
        from app.gateway.java_client import JavaApiError
        request=self.request().model_copy(update={"message":"加入购物车"})
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.post.side_effect=JavaApiError("购物车版本已变化，请刷新")
            result=execute(request,"prepare_campus_cart",'{"productType":"SKU","productId":1,"quantity":1,"expectedVersion":9}')
            self.assertFalse(result["ok"])
            self.assertEqual("cart_version_conflict",result["errorCategory"])
            client.return_value.post.assert_called_once()

    def test_search_adds_actual_merchant_name_for_same_name_disambiguation(self):
        with patch("app.agent.campus.JavaClient") as client:
            client.return_value.get.side_effect=[[{"merchantId":7,"id":12,"name":"米饭"}],[{"id":7,"name":"饭店"}]]
            self.assertEqual("饭店",execute(self.request(),"search_campus_menu",'{}')["data"][0]["merchantName"])

    def test_conversation_is_bounded_and_isolated_by_customer_and_fulfillment(self):
        from app.config import settings
        with patch.object(settings,"rate_limit_backend","memory"):
            request=self.request()
            messages=[{"role":"user","content":"只推荐"},{"role":"assistant","content":"青禾饭堂米饭和奶茶"}]
            _conversation(request,messages)
            for update in ({"userId":2},{"campusId":4},{"addressId":4},{"fulfillmentMode":"PICKUP"}):
                self.assertEqual([],_conversation(request.model_copy(update=update)))
            for _ in range(8):_conversation(request,messages)
            self.assertEqual(6,len(_conversation(request)))
            with patch("app.agent.campus.chat_with_tools",return_value=SimpleNamespace(content="已查询",tool_calls=[])) as model:
                result=run(request.model_copy(update={"message":"就按刚才那个"}))
                self.assertTrue(result["contextApplied"])
                self.assertIn(messages[1],model.call_args.args[0])

    def test_context_outage_fails_before_model_and_preserves_completed_tool_results(self):
        import redis
        with patch("app.agent.campus._conversation",side_effect=redis.ConnectionError), patch("app.agent.campus.chat_with_tools") as model:
            self.assertEqual("context_unavailable",run(self.request())["errorCategory"])
            model.assert_not_called()
        tool=SimpleNamespace(id="t1",function=SimpleNamespace(name="quote_campus_cart",arguments='{"merchantIds":[1]}'))
        responses=[SimpleNamespace(content="",tool_calls=[tool]),SimpleNamespace(content="报价完成",tool_calls=[])]
        with patch("app.agent.campus._conversation",side_effect=[[],redis.ConnectionError]), \
             patch("app.agent.campus.chat_with_tools",side_effect=responses), \
             patch("app.agent.campus.execute",return_value={"ok":True,"data":{"id":"real-quote","total":12}}):
            result=run(self.request())
            self.assertEqual("completed",result["outcome"])
            self.assertFalse(result["contextSaved"])
            self.assertEqual("real-quote",result["quote"]["id"])


if __name__ == "__main__":
    unittest.main()
