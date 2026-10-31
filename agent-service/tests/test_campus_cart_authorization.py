import json
import time
import unittest
from unittest.mock import patch

from app.agent.campus import CampusRequest, execute


class CampusCartAuthorizationTest(unittest.TestCase):
    def request(self, message):
        return CampusRequest(
            userId=1, campusId=2, addressId=3, fulfillmentMode="DELIVERY",
            message=message, jwtToken="test-token", requestId="cart-intent-test",
            deadlineEpochMs=int((time.time() + 30) * 1000),
        )

    def assert_read_only(self, messages):
        payload = json.dumps({"productType": "SKU", "productId": 1, "quantity": 1})
        for message in messages:
            with self.subTest(message=message), patch("app.agent.campus.JavaClient") as client:
                result = execute(self.request(message), "prepare_campus_cart", payload)
                self.assertFalse(result["ok"])
                self.assertEqual("cart_authorization_required", result["errorCategory"])
                self.assertIn("把刚才的组合加入购物车，每样一份", result["message"])
                client.return_value.post.assert_not_called()
                client.assert_not_called()

    def test_explanations_and_capability_questions_do_not_authorize_writes(self):
        self.assert_read_only([
            "告诉我如何准备购物车", "解释加入购物车按钮", "准备购物车是什么意思",
            "如何把奶茶加入购物车", "能否把奶茶加入购物车", "请问能不能帮我加入购物车",
            "你可以把奶茶加入购物车吗", "把奶茶加入购物车可以吗", "加购奶茶可以吗",
            "加购按钮在哪里", "加购功能有哪些限制", "加购按钮在哪儿",
            "加购哪种奶茶", "加购多少份", "加购怎么操作",
        ])

    def test_quotes_examples_and_hypotheticals_do_not_authorize_writes(self):
        self.assert_read_only([
            "假如把奶茶加入购物车会怎样", "举例说明怎么准备购物车", "例如：把奶茶加入购物车",
            "引用这句话：把奶茶加入购物车", "他说把奶茶加入购物车", "“把奶茶加入购物车”",
            '"把奶茶加入购物车"', "`把奶茶加入购物车`", "把奶茶加入购物车，这只是一个例句",
        ])

    def test_negations_and_mixed_or_incomplete_instructions_fail_closed(self):
        self.assert_read_only([
            "不要把奶茶加入购物车", "先别准备购物车", "不需要加购奶茶", "没有要求你加入购物车",
            "把奶茶不要加入购物车", "把奶茶别加入购物车", "把奶茶不要真的加入购物车",
            "把奶茶暂不加入购物车", "把奶茶不加入购物车", "把奶茶禁止加入购物车",
            "只查询优惠，把奶茶加入购物车", "先推荐奶茶，然后看是否加入购物车",
            "把奶茶加入购物车之前先问我", "把奶茶加入购物车，如果还有库存",
            "按推荐准备购物车，但先不要执行", "加入购物车后告诉我这个按钮是什么意思",
            "按刚才的推荐，每样一份", "不要下单支付", "帮我处理一下购物车",
        ])

    def test_direct_commands_and_recommendation_references_remain_supported(self):
        messages = [
            "把奶茶加入购物车", "请把刚才的组合加入购物车", "把上轮推荐的组合加入购物车，每样一份",
            "按推荐准备购物车，每样一份", "就按刚才推荐加入购物车，不要下单支付",
            "不要下单支付，只加入购物车", "加入购物车", "请帮我将米饭和奶茶加入购物车。",
            "加购", "请加购", "把奶茶加购", "把不加糖奶茶加入购物车", "请添加奶茶到购物车",
            "加购奶茶", "帮我加购奶茶", "把三杯鸡加入购物车",
            "把特别奶茶加入购物车", "按上轮推荐的组合加购，每样一份",
            "帮我加入购物车，先不下单也不支付", "把奶茶加购物车",
            "就按你刚才推荐的最便宜跨店组合，每样一份，帮我加入购物车，并用刚才查询到的店铺券和平台券报价；不要下单或支付。",
        ]
        payload = json.dumps({"productType": "SKU", "productId": 1, "quantity": 1})
        for message in messages:
            with self.subTest(message=message), patch("app.agent.campus.JavaClient") as client:
                client.return_value.post.return_value = 42
                result = execute(self.request(message), "prepare_campus_cart", payload)
                self.assertTrue(result["ok"])
                client.return_value.post.assert_called_once()
                self.assertEqual("/campus-assistant/prepare-cart", client.return_value.post.call_args.args[0])

    def test_common_quantity_expressions_use_the_explicit_target_total(self):
        for message in ["把奶茶加入购物车，两杯", "请帮我来两杯奶茶，加入购物车",
                        "我要两杯奶茶，加入购物车", "加购奶茶，每样2杯"]:
            with self.subTest(message=message), patch("app.agent.campus.JavaClient") as client:
                payload = json.dumps({"productType": "SKU", "productId": 1, "quantity": 2})
                self.assertTrue(execute(self.request(message), "prepare_campus_cart", payload)["ok"])
                self.assertEqual(2, client.return_value.post.call_args.args[2]["item"]["quantity"])

    def test_quantity_mismatch_or_conflicting_counts_do_not_reach_java(self):
        for message in ["把奶茶加入购物车，两杯", "加入购物车，每样两杯", "加入购物车，两杯，三杯"]:
            with self.subTest(message=message), patch("app.agent.campus.JavaClient") as client:
                result = execute(self.request(message), "prepare_campus_cart",
                                 json.dumps({"productType": "SKU", "productId": 1, "quantity": 1}))
                self.assertEqual("cart_quantity_mismatch", result["errorCategory"])
                client.assert_not_called()

    def test_new_expressions_keep_questions_negations_and_missing_authorization_read_only(self):
        self.assert_read_only(["帮我来两杯奶茶", "加购奶茶吗", "帮我加购奶茶，先确认再执行",
                               "帮我来不要加购的奶茶，加入购物车", "加购奶茶，先不要执行",
                               "加购奶茶之前先问我", "例如加购奶茶，每样两杯"])

    def test_rejected_write_returns_clarification_without_another_model_call(self):
        from types import SimpleNamespace
        from app.agent.campus import run
        tool = SimpleNamespace(id="quantity", function=SimpleNamespace(name="prepare_campus_cart",
            arguments=json.dumps({"productType": "SKU", "productId": 1, "quantity": 1})))
        with patch("app.agent.campus._conversation", return_value=[]), \
                patch("app.agent.campus.chat_with_tools", return_value=SimpleNamespace(tool_calls=[tool], content="")) as model, \
                patch("app.agent.campus.JavaClient") as client:
            result = run(self.request("把奶茶加入购物车，两杯"))
        self.assertEqual("needs_input", result["outcome"])
        self.assertEqual("cart_quantity_mismatch", result["errorCategory"])
        self.assertIn("该商品未修改", result["reply"])
        self.assertEqual(1, model.call_count)
        client.assert_not_called()

    def test_clarification_preserves_completed_writes_but_drops_an_earlier_quote(self):
        from types import SimpleNamespace
        from app.agent.campus import run
        def tool(index, name, args):
            return SimpleNamespace(id=str(index), function=SimpleNamespace(name=name, arguments=json.dumps(args)))
        item = {"productType": "SKU", "productId": 1, "quantity": 2}
        pending = [tool(1, "prepare_campus_cart", item), tool(2, "quote_campus_cart", {"merchantIds": [1]}),
                   tool(3, "prepare_campus_cart", {**item, "productId": 2, "quantity": 1}),
                   tool(4, "prepare_campus_cart", {**item, "productId": 3})]
        with patch("app.agent.campus._conversation", return_value=[]), \
                patch("app.agent.campus.chat_with_tools", return_value=SimpleNamespace(tool_calls=pending, content="")) as model, \
                patch("app.agent.campus.JavaClient") as client:
            client.return_value.post.side_effect = [42, {"id": "earlier-quote"}]
            result = run(self.request("加入购物车，每样两杯"))
        self.assertEqual("needs_input", result["outcome"])
        self.assertIn("已有商品修改成功", result["reply"])
        self.assertNotIn("quote", result)
        self.assertEqual(["ok", "ok", "error"], [call["status"] for call in result["toolCalls"]])
        self.assertEqual(2, client.return_value.post.call_count)
        self.assertEqual(1, model.call_count)

    def test_negation_within_a_target_command_blocks_every_supported_cart_action(self):
        actions = ["加入购物车", "添加到购物车", "加到购物车", "加购物车", "放入购物车",
                   "准备购物车", "更新购物车", "修改购物车", "加购"]
        self.assert_read_only(["把奶茶" + negative + action for negative in ("不", "别", "不要") for action in actions])


if __name__ == "__main__":
    unittest.main()
