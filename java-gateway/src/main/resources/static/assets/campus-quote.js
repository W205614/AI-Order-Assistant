import { api, notice, fmt } from "./api.js";
import { node, button, money } from "./commerce-ui.js";

export function createQuoteController({
  $,
  getCart,
  getShops,
  getCoupons,
  hasPendingSafety,
  requireSafetySaved,
  context,
  couponSelection,
  loadCart,
  refresh,
}) {
  let quote,
    quoteKey,
    quoteRevision = 0;
  function invalidate() {
    quoteRevision++;
    quote = undefined;
    $("#quote").replaceChildren(
      node("p", "购物车或结算条件变化后，请重新计算报价。"),
    );
  }
  async function price() {
    requireSafetySaved();
    const revision = quoteRevision;
    const merchantIds = getCart().selectedMerchants();
    if (!merchantIds.length) throw Error("请勾选需要结算的店铺");
    const candidate = await api("/checkouts/quotes", {
      method: "POST",
      body: {
        ...context(),
        ...couponSelection(),
        merchantIds,
        reservationSlots: getCart().selectedReservationSlots(merchantIds),
      },
    });
    if (revision !== quoteRevision) throw Error("结算条件已变化，请重新报价");
    renderQuote(candidate);
  }
  function renderQuote(current) {
    quote = current;
    quoteKey = crypto.randomUUID();
    const el = $("#quote");
    el.replaceChildren(
      node("h3", "请核对本次报价"),
      node("p", "报价有效至 " + fmt(quote.expiresAt) + "，提交时会重新核验。", {
        class: "muted",
      }),
    );
    for (const s of quote.shops) {
      const box = node("div", undefined, { class: "quote-shop" });
      box.append(
        node("strong", s.merchantName),
        node(
          "p",
          s.lines.map((l) => l.product.name + " × " + l.quantity).join("、"),
          { class: "muted" },
        ),
      );
      for (const [label, amount] of [
        ["商品", s.goods],
        ["包装费", s.packaging],
        ["配送费", s.delivery],
        ["店铺优惠", -s.shopDiscount],
        ["平台优惠", -s.platformDiscount],
      ]) {
        const row = node("div", undefined, { class: "price-row" });
        row.append(node("span", label), node("span", money(amount)));
        box.append(row);
      }
      const total = node("div", undefined, { class: "price-row total-row" });
      total.append(node("span", "本店实付"), node("span", money(s.payable)));
      box.append(
        total,
        node("p", "预计履约区间 " + s.etaMin + "–" + s.etaMax + " 分钟", {
          class: "muted",
        }),
      );
      el.append(box);
    }
    el.append(node("strong", "本次合计 " + money(quote.total)));
    const failed = Object.entries(quote.unavailable);
    if (failed.length) {
      for (const [m, reason] of failed)
        el.append(
          node(
            "p",
            (getShops().find((s) => s.id === Number(m))?.name ?? "所选店铺") +
              "：" +
              reason,
            { class: "error" },
          ),
        );
      el.append(
        button("仅选择可提交店铺，重新计算报价", async () => {
          for (const [m] of failed) {
            if (Number(m) === 0) {
              $("#platformCoupon").value = "";
              continue;
            }
            getCart().deselectMerchant(Number(m));
          }
          await loadCart();
          await price();
        }),
      );
    } else if (quote.shops.length)
      el.append(
        button("确认本次报价并创建订单", async () => {
          const current = quote;
          if (!current) throw Error("请重新计算报价");
          await api("/checkouts/confirm", {
            method: "POST",
            headers: { "Idempotency-Key": quoteKey },
            body: { quoteId: current.id, expectedVersion: current.version },
          });
          invalidate();
          await refresh();
          notice("订单已创建，请在十分钟内模拟支付。");
        }),
      );
  }
  function compatibleAssistantQuote(candidate) {
    if (!candidate?.request || !Array.isArray(candidate.shops)) return false;
    const current = context(),
      requested = candidate.request;
    if (
      Number(requested.campusId) !== current.campusId ||
      requested.fulfillmentMode !== current.fulfillmentMode ||
      Number(requested.addressId ?? 0) !== Number(current.addressId ?? 0)
    )
      return false;
    const expiry = String(candidate.expiresAt ?? "");
    const expiresAt = new Date(
      /[Zz]|[+-]\d\d:\d\d$/.test(expiry)
        ? expiry
        : expiry.replace(" ", "T") + "+08:00",
    );
    if (
      !Number.isFinite(expiresAt.getTime()) ||
      expiresAt.getTime() <= Date.now()
    )
      return false;
    for (const shop of candidate.shops) {
      const items = getCart().items.filter(
        (item) => Number(item.merchant_id) === Number(shop.merchantId),
      );
      if (items.length !== shop.lines.length) return false;
      for (const line of shop.lines) {
        const item = items.find(
          (item) => Number(item.id) === Number(line.cartId),
        );
        if (
          !item ||
          Number(item.version) !== Number(line.cartVersion) ||
          Number(item.quantity) !== Number(line.quantity) ||
          item.product_type !== line.product.type ||
          Number(item.product_id) !== Number(line.product.id)
        )
          return false;
      }
    }
    return true;
  }
  async function applyAssistantQuote(candidate, expectedRevision) {
    if (
      hasPendingSafety() ||
      expectedRevision !== quoteRevision ||
      !compatibleAssistantQuote(candidate)
    )
      return false;
    const requested = candidate.request;
    if (
      requested.platformCoupon &&
      !getCoupons().some(
        (c) =>
          Number(c.id) === Number(requested.platformCoupon) &&
          c.status === "AVAILABLE" &&
          c.merchant_id === null,
      )
    )
      return false;
    for (const [merchant, id] of Object.entries(requested.shopCoupons ?? {}))
      if (
        !getCoupons().some(
          (c) =>
            Number(c.id) === Number(id) &&
            Number(c.merchant_id) === Number(merchant) &&
            c.status === "AVAILABLE",
        )
      )
        return false;
    getCart().applyQuoteSelection(requested);
    $("#platformCoupon").value = requested.platformCoupon
      ? String(requested.platformCoupon)
      : "";
    await loadCart();
    if (
      expectedRevision !== quoteRevision ||
      !compatibleAssistantQuote(candidate)
    )
      return false;
    renderQuote(candidate);
    return true;
  }

  return {
    invalidate,
    price,
    applyAssistantQuote,
    get revision() {
      return quoteRevision;
    },
  };
}
