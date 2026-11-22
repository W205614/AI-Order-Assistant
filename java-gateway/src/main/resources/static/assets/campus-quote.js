import { accountSignal, api, fmt, state } from "./api.js";
import { node, button, money } from "./commerce-ui.js";
import { capabilities, paymentLabel, startPayment } from "./payment-session.js";

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
    quoteKey = undefined;
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
  const confirmationKey = () => "campus-confirmation:" + state.me.userId;
  function savedConfirmation() {
    const saved = sessionStorage.getItem(confirmationKey());
    if (!saved) return null;
    const [id, version, key] = saved.split(":");
    return /^[a-f0-9-]{36}$/i.test(id) && /^[a-f0-9-]{36}$/i.test(key)
      && Number.isSafeInteger(Number(version)) && Number(version) > 0
      ? { id, version: Number(version), key } : null;
  }

  async function confirmCheckout(pending) {
    const storageKey = confirmationKey();
    const signature = [pending.id, pending.version, pending.key].join(":");
    const saved = sessionStorage.getItem(storageKey);
    if (saved && saved !== signature)
      throw Error("请先核对上次确认结果，再提交新订单。");
    sessionStorage.setItem(storageKey, signature);
    try {
      const checkout = await api("/checkouts/confirm", {
        method: "POST",
        headers: { "Idempotency-Key": pending.key },
        body: { quoteId: pending.id, expectedVersion: pending.version },
      });
      sessionStorage.removeItem(storageKey);
      return checkout;
    } catch (error) {
      if (error.status >= 400 && error.status < 500 && ![401, 403, 429].includes(error.status))
        sessionStorage.removeItem(storageKey);
      throw error;
    }
  }

  function offerConfirmationRecovery() {
    if (!state.me?.userId) return;
    const pending = savedConfirmation();
    if (!pending) return;
    const panel = $("#checkoutPayment");
    const lifetime = accountSignal();
    panel.replaceChildren(node("p", "上次订单确认结果待核对。"));
    panel.append(button("核对上次订单确认", async () => {
      try {
        const checkout = await confirmCheckout(pending);
        lifetime.throwIfAborted();
        await showCheckoutPayment(checkout);
        await refresh();
      } catch (error) {
        if (!lifetime.aborted && !savedConfirmation()) {
          panel.replaceChildren(node("p", error.message, { class: "error" }));
          panel.append(button("重新计算并核对报价", price));
          await refresh();
        }
        throw error;
      }
    }));
  }

  function editCart() {
    $("#cartSection").scrollIntoView({ block: "start" });
    $("#campusCart input, #quoteButton").focus({ preventScroll: true });
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
    el.append(node("strong", "本次合计 " + money(quote.total)), button("返回修改购物车", editCart));
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
          let checkout;
          try {
            checkout = await confirmCheckout({ id: current.id, version: current.version, key: quoteKey });
          } catch (error) {
            if (error.code !== "REQUOTE_REQUIRED" || quote !== current) {
              offerConfirmationRecovery();
              throw error;
            }
            invalidate();
            const changes = error.data;
            el.replaceChildren(node("p", error.message, { class: "error" }));
            if (changes?.addressChanged) el.append(node("p", "收货地址已更新。"));
            for (const [merchant, reason] of Object.entries(changes?.unavailable ?? {})) {
              const shop = current.shops.find((s) => Number(s.merchantId) === Number(merchant));
              el.append(node("p", (shop?.merchantName ?? "本次结算") + " · " + reason));
            }
            if (changes?.currentTotal != null &&
                Number(changes.currentTotal) !== Number(changes.previousTotal))
              el.append(node("p", "原实付 " + money(changes.previousTotal) +
                "，当前实付 " + money(changes.currentTotal)));
            for (const change of changes?.priceChanges ?? [])
              el.append(node("p", change.label + " · " + money(change.before) + " → " + money(change.after)));
            el.append(button("重新计算并核对报价", price), button("返回修改购物车", editCart));
            return;
          }
          if (quote === current) invalidate();
          await showCheckoutPayment(checkout);
          await refresh();
        }),
      );
  }
  async function showCheckoutPayment(checkout) {
    const panel = $("#checkoutPayment");
    panel.replaceChildren(node("h3", "本次结算已创建"));
    if (checkout.status !== "UNPAID") {
      panel.append(node("p", "请在我的订单查看当前状态。"));
      return;
    }
    const deadline = new Date(checkout.expires_at.replace(" ", "T") + "+08:00").getTime();
    const status = node("p", "应付 " + money(checkout.total_amount) +
      " · 请在 " + fmt(checkout.expires_at) + " 前付款");
    const config = await capabilities();
    const pay = button(paymentLabel(config, true), async () => {
      await startPayment("CHECKOUT", checkout.id, "/checkouts/" + checkout.id + "/pay", async () => {
        const updated = await api("/checkouts/" + checkout.id);
        if (updated.status !== "UNPAID" && panel.contains(pay)) {
          pay.remove();
          status.textContent = updated.status === "PAID"
            ? "付款完成，可在我的订单查看各店进度。"
            : "本次结算已关闭，可在我的订单查看。";
        }
        await refresh();
      });
    });
    const countdown = node("p");
    function tick() {
      if (!panel.contains(pay)) return;
      const seconds = Math.ceil((deadline - Date.now()) / 1000);
      if (seconds <= 0) {
        countdown.textContent = "支付时间已到，请在我的订单核对状态。";
        pay.disabled = true;
        return;
      }
      countdown.textContent = "剩余 " + Math.floor(seconds / 60) + " 分 " + (seconds % 60) + " 秒";
      setTimeout(tick, 1000);
    }
    panel.append(status, countdown, pay);
    tick();
    pay.focus();
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
    offerConfirmationRecovery,
    get revision() {
      return quoteRevision;
    },
  };
}
