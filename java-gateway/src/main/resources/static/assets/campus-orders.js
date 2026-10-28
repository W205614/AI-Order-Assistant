import { api, notice } from "./api.js";
import { node, button, card, form, money, labels } from "./commerce-ui.js";

export function createOrdersController({
  $,
  requireSafetySaved,
  invalidate,
  loadCart,
  refresh,
  loadFeedback,
}) {
  let ordersRevision = 0,
    orderPages = 1;
  async function loadOrders({ preserveEditing = false } = {}) {
    const revision = ++ordersRevision,
      el = node("div");
    const checkouts = new Map();
    let more = false;
    for (let page = 1; page <= orderPages; page++) {
      const batch = await api("/checkouts?page=" + page + "&pageSize=20");
      for (const c of batch) checkouts.set(c.id, c);
      more = batch.length === 20;
      if (!more) break;
    }
    for (const c of checkouts.values()) {
      const checkout = await api("/checkouts/" + c.id),
        box = card(
          "结算 · " +
            (labels[c.status] ?? c.status) +
            " · " +
            money(c.total_amount) +
            " · 已退" +
            money(c.refunded_amount),
        );
      box.dataset.checkoutId = c.id;
      if (c.status === "UNPAID")
        box.append(
          button("统一模拟支付", async () => {
            await api("/checkouts/" + c.id + "/pay", { method: "POST" });
            await refresh();
          }),
          button("关闭未支付结算", async () => {
            await api("/checkouts/" + c.id + "/close", { method: "POST" });
            await refresh();
          }),
        );
      for (const o of checkout.orders) {
        const order = card(
          o.merchant_name +
            " · " +
            (labels[o.fulfillment_status] ?? o.fulfillment_status),
        );
        order.append(
          node(
            "p",
            o.items.map((i) => i.dish_name + " × " + i.quantity).join("、"),
          ),
          node(
            "p",
            "实付" +
              money(o.total_amount) +
              " · 已退" +
              money(o.refunded_amount),
          ),
        );
        if (o.status === 1 && !o.production_started)
          order.append(
            button("取消本店订单", async () => {
              await api("/checkouts/orders/" + o.id + "/cancel", {
                method: "POST",
              });
              await refresh();
            }),
          );
        if (o.status >= 1 && o.status <= 3)
          order.append(
            button("查看本人取餐码", async () => {
              const code = await api("/checkouts/orders/" + o.id + "/code");
              notice("取餐码：" + code + "，请仅在取餐或确认送达时提供。");
            }),
          );
        order.append(
          button("再来一单", async () => {
            requireSafetySaved();
            await api("/cart/reorder/" + o.id, { method: "POST" });
            invalidate();
            await loadCart();
          }),
        );
        if (o.status > 0 && o.status < 5) {
          const refund = form(
            [
              ...o.items
                .filter((i) => i.refundable_quantity > 0)
                .map((i) => [
                  "item" + i.id,
                  "退款数量：" +
                    i.dish_name +
                    "（最多" +
                    i.refundable_quantity +
                    "）",
                  "number",
                  "0",
                ]),
              ["reason", "退款原因", "text", ""],
            ],
            "提交按数量退款",
            async (d) => {
              const items = o.items
                .map((i) => ({
                  orderItemId: i.id,
                  quantity: Number(d["item" + i.id] ?? 0),
                }))
                .filter((i) => i.quantity > 0);
              await api("/aftersales", {
                method: "POST",
                body: {
                  orderId: o.id,
                  items,
                  reason: d.reason,
                  requestKey: crypto.randomUUID(),
                },
              });
              await refresh();
            },
          );
          order.append(refund);
        }
        for (const a of o.aftersales) {
          order.append(
            node(
              "p",
              "售后 " +
                (labels[a.status] ?? a.status) +
                " · " +
                money(a.amount) +
                " · " +
                (a.decision_reason ?? a.reason),
            ),
          );
          if (a.status === "REJECTED")
            order.append(
              form(
                [["reason", "申诉原因", "text", ""]],
                "提交平台申诉",
                async (d) => {
                  await api("/aftersales/" + a.id + "/dispute", {
                    method: "POST",
                    body: d,
                  });
                  await loadOrders();
                },
              ),
            );
        }
        order.append(
          form([["message", "意见反馈", "text", ""]], "提交反馈", async (d) => {
            await api("/feedback", {
              method: "POST",
              body: { orderId: o.id, message: d.message },
            });
            await loadFeedback();
          }),
        );
        box.append(order);
      }
      el.append(box);
    }
    if (!el.children.length)
      el.append(
        node(
          "p",
          "还没有结算订单。选好商品、核对报价后，你的订单会显示在这里。",
          { class: "empty-state" },
        ),
      );
    if (more)
      el.append(
        button("加载更多结算", async () => {
          orderPages++;
          try {
            await loadOrders();
          } catch (e) {
            orderPages--;
            throw e;
          }
        }),
      );
    if (revision !== ordersRevision) return false;
    if (preserveEditing && $("#checkoutList").contains(document.activeElement))
      return false;
    $("#checkoutList").replaceChildren(...el.childNodes);
    return true;
  }

  return { loadOrders };
}
