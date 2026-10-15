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
            "加购奶茶", "帮我加购奶茶",
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

    def test_negation_within_a_target_command_blocks_every_supported_cart_action(self):
        actions = ["加入购物车", "添加到购物车", "加到购物车", "加购物车", "放入购物车",
                   "准备购物车", "更新购物车", "修改购物车", "加购"]
        self.assert_read_only(["把奶茶" + negative + action for negative in ("不", "别", "不要") for action in actions])


if __name__ == "__main__":
    unittest.main()
