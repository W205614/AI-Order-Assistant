"""Render operational claims only from the Java-signed request's typed capability snapshot."""

from __future__ import annotations

from typing import Any


def policy_summary(capabilities: dict[str, Any] | None) -> str:
    if capabilities is None:
        payment = "当前支付模式尚未核验，请以页面标识和订单查询结果为准，不能推测是否真实扣款。"
    elif capabilities["paymentMode"] == "SIMULATED":
        payment = "当前是模拟支付与模拟退款，不发生真实扣款或原路退款。"
    else:
        payment = (
            "当前支持微信真实支付，由本人在页面打开收银台确认。"
            "网络超时或渠道结果未知不代表未扣款，先查询支付状态，不反复付款。"
            "售后审核通过不等于退款到账；只有渠道确认退款成功才称已退款，不承诺到账时间。"
        )
    if capabilities is None:
        sms = "短信能力尚未核验，不能声称已发送或送达。"
    elif capabilities["sms"]:
        sms = "已配置短信能力；是否发送以通知任务记录为准，渠道受理不等于用户收到。"
    else:
        sms = "短信能力未启用，使用站内通知；不能声称已发短信。"
    return (payment + sms + "历史模拟记录仍是模拟，不能把当前支付模式套用于历史单；"
            "缺少单据资金模式时，不宣称真实扣款或退款到账。"
            "订单事实以Java查询为准，支付、退款、取消均不能由模型擅自执行。")


def runtime_faq_item(item: dict, capabilities: dict[str, Any] | None) -> dict:
    title = item["title"]
    payment = policy_summary(capabilities)
    answers = {
        "如何申请退款": "商家开始制作前可在订单页申请取消；制作后按商品数量申请售后。查看审核结果及独立退款进度。" + payment,
        "支付失败怎么办": "请先在订单页面查询付款状态。网络失败不等于没有扣款，不要重复付款；结果未知时等待查单或联系平台处理。" + payment,
        "支付方式有哪些": payment,
        "订单多久能送到": "请查看店铺展示的预计时间与当前履约状态。骑行路线时间不包含备餐，也不是送达保证；由商家和骑手实际推进履约。",
        "订单超时/配送慢了怎么办": "未支付订单有支付期限。支付结果未知须先核对渠道，不能自行断言订单已关闭。已支付但等待较久可在订单页催单或反馈，催单不保证立即响应。",
        "如何查询订单状态": "请提供订单号或说明最近一笔订单，我会查询本人订单。备餐、配送和付款退款是不同状态，不能相互推断。",
        "如何催单": "在订单允许催单的状态点击催单，系统持久记录并通知商户；相邻催单需间隔六十秒。不能推测商家已阅读。" + payment,
        "如何取消订单": "请在页面核对并确认取消。开始制作后改为申请售后；取消成功、退款处理中与退款成功分别展示。" + payment,
        "如何联系商家/客服": "可在订单页提交意见反馈、售后或平台申诉。系统不能代替人工客服，也不能保证处理时限。",
        "什么是待接单状态": "支付确认后等待店主或店员接单，尚不代表已经制作。商家和骑手按实际进度更新状态。" + payment,
        "配送范围": "配送资格按店铺开放的校区和当前配送规则校验。地图候选和骑行路线仅辅助选址、导航，不能据此绕过校区限制或保证可配送。",
    }
    return {**item, "answer": answers.get(title, item["answer"])}
