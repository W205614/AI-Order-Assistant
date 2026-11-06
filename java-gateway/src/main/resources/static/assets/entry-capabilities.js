import { capabilities } from "./payment-session.js";

const label = document.querySelector(".page-footer span:last-child");
capabilities().then((config) => {
  label.textContent = config.paymentMode === "SIMULATED"
    ? "当前为模拟支付，订单不会产生真实扣款。"
    : "已启用微信支付，付款与退款以服务器及渠道确认结果为准。";
}).catch(() => {
  label.textContent = "支付模式暂未核验，请进入订单页确认。";
});
