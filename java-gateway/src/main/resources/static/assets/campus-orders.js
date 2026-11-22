import { accountSignal, api, notice, paymentName, fmt, onForm, run } from "./api.js";
import { node, button, card, field, form, money, labels } from "./commerce-ui.js";
import { capabilities, paymentLabel, reopenPayment, startPayment } from "./payment-session.js";
import { mediaControl } from "./integrations.js";

export function createOrdersController({
  $,
  requireSafetySaved,
  invalidate,
  loadCart,
  refresh,
  loadFeedback,
}) {
  let ordersRevision = 0,
    orderPages = 1,
    historyFilters = {},
    followedCheckout;
  onForm("historyFilter", async (values) => {
    historyFilters = Object.fromEntries(Object.entries(values).filter(([, value]) => value.trim()));
    orderPages = 1;
    await loadOrders();
  });
  $("#historyFilter").addEventListener("reset", () => {
    historyFilters = {};
    orderPages = 1;
    run(loadOrders);
  });
  async function loadOrders({ preserveEditing = false } = {}) {
    const revision = ++ordersRevision,
      scope = $("#checkoutScope").value,
      el = node("div");
    const checkouts = new Map();
    const config = await capabilities();
    let more = false;
    for (let page = 1; page <= orderPages; page++) {
      const query = new URLSearchParams({ scope, page, pageSize: 20,
        ...(scope === "history" ? historyFilters : {}) });
      const batch = await api("/checkouts?" + query);
      for (const c of batch) checkouts.set(c.id, c);
      more = batch.length === 20;
      if (!more) break;
    }
    const opened = new Set(
      [...$("#checkoutList").querySelectorAll("details[open][data-checkout-id]")]
        .map((details) => details.dataset.checkoutId),
    );
    const summaries = [...checkouts.values()];
    if (scope === "all" && followedCheckout) {
      const index = summaries.findIndex((c) => c.id === followedCheckout);
      if (index >= 0) summaries.splice(index, 1);
      summaries.unshift({ id: followedCheckout });
    }
    for (const c of summaries) {
      if (scope !== "history") {
        el.append(renderCheckout(await api("/checkouts/" + c.id), config));
        continue;
      }
      const details = node("details", undefined, { class: "checkout-history" });
      details.dataset.checkoutId = c.id;
      const statuses = (c.fulfillment_statuses || c.status).split(",")
        .map((status) => labels[status] ?? status).join(" / ");
      details.append(node("summary",
        (c.merchant_names || "校园订单") + " · " + fmt(c.created_at) + " · " +
        money(c.total_amount) + " · " + statuses));
      const detail = node("div");
      details.append(detail);
      const lifetime = accountSignal();
      let loaded = false;
      async function expand() {
        if (loaded) return;
        loaded = true;
        detail.replaceChildren(node("p", "正在加载订单明细…"));
        try {
          const checkout = await api("/checkouts/" + c.id, { signal: lifetime });
          if (!lifetime.aborted) {
            const rendered = renderCheckout(checkout, config);
            delete rendered.dataset.checkoutId;
            detail.replaceChildren(rendered);
          }
        } catch (error) {
          if (lifetime.aborted) return;
          loaded = false;
          detail.replaceChildren(node("p", error.message, { class: "error" }),
            button("重新加载订单明细", expand));
        }
      }
      details.addEventListener("toggle", () => {
        if (details.open) void expand();
      });
      if (opened.has(c.id)) details.open = true;
      el.append(details);
    }
    if (!el.children.length)
      el.append(
        node(
          "p",
          { active: "当前没有进行中的订单。", history: "当前没有历史订单。", all: "还没有结算订单。" }[scope],
          { class: "empty-state" },
        ),
      );
    if (!checkouts.size && scope === "active")
      el.append(button("查看历史订单", async () => {
        $("#checkoutScope").value = "history";
        orderPages = 1;
        await loadOrders();
      }));
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
    $("#historyFilter").hidden = scope !== "history";
    $("#checkoutList").replaceChildren(...el.childNodes);
    if (scope === "active") {
      const overview = $("#activeOrderSummary");
      overview.replaceChildren();
      overview.hidden = !checkouts.size;
      if (checkouts.size) overview.append(button(
        "查看进行中的订单 · " + checkouts.size + (more ? "+" : "") + " 笔",
        async () => {
          if ($("#checkoutScope").value !== "active") {
            $("#checkoutScope").value = "active";
            orderPages = 1;
            await loadOrders();
          }
          $("#ordersSection").scrollIntoView({ block: "start" });
          $("#checkoutScope").focus({ preventScroll: true });
        }));
    }
    return true;
  }

  async function followCheckout(id) {
    followedCheckout = id;
    orderPages = 1;
    $("#checkoutScope").value = "all";
    await refresh();
  }

  function renderCheckout(checkout, config) {
    const c = checkout;
    const box = card("结算 · " + (labels[c.status] ?? c.status) + " · " + money(c.total_amount) + " · 已退" + money(c.refunded_amount));
    box.dataset.checkoutId = c.id;
    if (c.status === "UNPAID") {
      const checking = ["UNKNOWN", "REVIEW", "CLOSED"].includes(c.channel_status);
      box.append(
        node("p", checking ? "付款结果核实中" : "待付款 · 截止 " + fmt(c.expires_at)),
        button(checking ? "核对付款结果" : paymentLabel(config, true), () =>
          c.channel_session ? reopenPayment(c.channel_session, refresh) :
            startPayment("CHECKOUT", c.id, "/checkouts/" + c.id + "/pay", refresh)),
        button("关闭未支付结算", async () => {
          await api("/checkouts/" + c.id + "/close", { method: "POST" });
          await refresh();
        }),
      );
    }
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
        node("p", "付款状态：" + paymentName(o.payment_status, o.payment_mode)),
      );
      const pickup = o.fulfillment_mode === "PICKUP";
      const progress = o.delivery_status
        ? labels[o.delivery_status] ?? o.delivery_status
        : labels[o.fulfillment_status] ?? o.fulfillment_status;
      const next = {
        UNPAID: "请完成付款",
        PENDING_ACCEPTANCE: "等待商家接单",
        ACCEPTED: "等待商家开始制作",
        PREPARING: "等待备餐完成",
        READY: pickup ? "请到店出示取餐码" : "等待骑手取餐",
        DELIVERING: "请留意骑手联系，交接时出示送达码",
      }[o.fulfillment_status];
      order.append(node("p", progress + " · 更新于 " + fmt(o.progress_updated_at)));
      if (pickup) order.append(node("p", "取餐点：" + (o.pickup_point || "请向店铺确认")));
      if (o.delivery_status === "EXCEPTION")
        order.append(node("p", "配送异常正在由平台处理，请留意订单更新。"));
      else if (next) order.append(node("p", next));
      if (Number(o.refund_pending_amount) > 0)
        order.append(node("p", "待渠道确认退款：" + money(o.refund_pending_amount)));
      if (o.status === 1 && !o.production_started)
        order.append(
          button("取消本店订单", async () => {
            await api("/checkouts/orders/" + o.id + "/cancel", {
              method: "POST",
            });
            await refresh();
          }),
        );
      if (o.status >= 1 && o.status <= 3) {
        const codeDisplay = node("p", "", { "aria-live": "polite" });
        order.append(
          button(pickup ? "查看本人取餐码" : "查看本人送达码", async () => {
            const code = await api("/checkouts/orders/" + o.id + "/code");
            codeDisplay.textContent = (pickup ? "取餐码：" : "送达码：") + code + "，请仅在交接时提供。";
          }),
          codeDisplay,
        );
      }
      const repeatPanel = node("div", undefined, { "aria-live": "polite" });
      order.append(
        button("再来一单", async () => {
          requireSafetySaved();
          const rows = await api("/cart/reorder/" + o.id + "/preview");
          repeatPanel.replaceChildren(node("h4", "核对本次复购"));
          const selection = new Map();
          for (const row of rows) {
            const title = row.name + " × " + row.quantity;
            if (row.unavailable) {
              repeatPanel.append(node("p", title + " · " + row.unavailable));
              continue;
            }
            const input = node("input", undefined, { type: "checkbox" });
            input.checked = true;
            selection.set(row, input);
            const price = Number(row.previousPrice) === Number(row.currentPrice)
              ? money(row.currentPrice)
              : "原单价 " + money(row.previousPrice) + " → 现价 " + money(row.currentPrice);
            repeatPanel.append(field(title + " · " + price, input));
          }
          if (selection.size) repeatPanel.append(button("将所选商品加入购物车", async () => {
            requireSafetySaved();
            const items = [...selection].filter(([, input]) => input.checked)
              .map(([row]) => ({ itemId: row.itemId, productVersion: row.version }));
            if (!items.length) throw Error("请至少选择一项可复购商品。");
            await api("/cart/reorder/" + o.id + "/reviewed", { method: "POST", body: items });
            invalidate();
            await loadCart();
            repeatPanel.replaceChildren(node("p", "已加入购物车，请按当前规则重新报价。"));
          }));
        }),
        repeatPanel,
      );
      const refundable = o.items.filter((i) => i.refundable_quantity > 0);
      if (o.status > 0 && o.status < 5 && refundable.length) {
        const preview = node("p", "", { "aria-live": "polite" });
        let reviewed = null;
        function selected() {
          return refundable.map((i) => ({
            orderItemId: i.id,
            quantity: Number(refund.elements["item" + i.id].value),
          })).filter((i) => i.quantity > 0);
        }
        const refund = form(
          [
            ...refundable.map((i) => [
              "item" + i.id, "退款数量 " + i.dish_name + "（最多" + i.refundable_quantity + "）", "number", "0"
            ]),
            ["reason", "退款原因", "text", ""],
          ],
          "确认提交退款",
          async ({ reason }) => {
            const items = selected();
            const signature = JSON.stringify(items);
            if (!reviewed || reviewed.signature !== signature) throw Error("请先核算本次预计退款。");
            if (reason !== reviewed.reason) {
              reviewed.reason = reason;
              reviewed.requestKey = crypto.randomUUID();
            }
            await api("/aftersales", {
              method: "POST",
              body: { orderId: o.id, items, reason, requestKey: reviewed.requestKey },
            });
            await followCheckout(c.id);
          },
        );
        for (const i of refundable) {
          const quantity = refund.elements["item" + i.id];
          quantity.min = "0";
          quantity.max = String(i.refundable_quantity);
          quantity.step = "1";
          quantity.addEventListener("input", () => {
            reviewed = null;
            submit.disabled = true;
            preview.textContent = "";
          });
        }
        const submit = refund.querySelector("button");
        submit.disabled = true;
        const calculate = button("核算预计退款", async () => {
          const items = selected();
          const signature = JSON.stringify(items);
          const result = await api("/aftersales/preview", { method: "POST", body: { orderId: o.id, items } });
          if (JSON.stringify(selected()) !== signature) return;
          reviewed = { signature };
          preview.textContent = "预计退款 " + money(result.amount) +
            (Number(result.deliveryRefund) ? "，含配送费 " + money(result.deliveryRefund) : "") +
            (result.beforePreparation ? " · 制作前处理" : " · 由商家审核");
          submit.disabled = false;
        });
        refund.insertBefore(calculate, submit);
        refund.insertBefore(preview, submit);
        order.append(refund, node("p", "制作前取消本店全部商品且券仍有效时返店铺券，整份结算均满足时返平台券。", { class: "muted" }));
      }
      for (const a of o.aftersales) {
        order.append(
          node(
            "p",
            "售后 " +
              (labels[a.status] ?? a.status) +
              ({ PENDING: " · 商家处理", DISPUTED: " · 平台处理", REFUNDING: " · 等待渠道确认", REFUND_FAILED: " · 平台核实" }[a.status] ?? "") +
              " · " +
              money(a.amount) +
              " · " +
              (a.decision_reason ?? a.reason),
          ),
        );
        order.append(mediaControl("AFTERSALE", a.id));
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
    return box;
  }

  return {
    loadOrders,
    changeScope() {
      followedCheckout = undefined;
      orderPages = 1;
      return loadOrders();
    },
    reset() {
      followedCheckout = undefined;
      ordersRevision++;
      orderPages = 1;
      $("#checkoutScope").value = "active";
      historyFilters = {};
      $("#historyFilter").hidden = true;
      $("#activeOrderSummary").hidden = true;
      $("#activeOrderSummary").replaceChildren();
      $("#checkoutList").replaceChildren();
    },
  };
}
