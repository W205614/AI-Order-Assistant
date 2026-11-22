import { api, notice } from "./api.js";
import { node, button, card, field, select, money } from "./commerce-ui.js";

export function createCartController({
  $,
  requireSafetySaved,
  invalidate,
  couponChoices,
  getShops,
  context,
}) {
  let cart = [], cartRevision = 0;
  const selections = new Map(),
    shopCoupons = new Map(),
    slots = new Map();
  function couponSelection() {
    return {
      shopCoupons: Object.fromEntries(
        [...shopCoupons].filter(([m, id]) => selections.get(m) && id),
      ),
      platformCoupon: $("#platformCoupon").value
        ? Number($("#platformCoupon").value)
        : null,
    };
  }
  function selectedMerchants() {
    return [...new Set(cart.map((item) => item.merchant_id))].filter(
      (merchant) => selections.get(merchant),
    );
  }
  function selectedReservationSlots(merchants) {
    return Object.fromEntries(
      [...slots].filter(([merchant, id]) => merchants.includes(merchant) && id),
    );
  }
  function deselectMerchant(merchant) {
    selections.set(merchant, false);
    shopCoupons.delete(merchant);
    slots.delete(merchant);
  }
  function applyQuoteSelection(request) {
    const selected = new Set(request.merchantIds.map(Number));
    for (const item of cart)
      selections.set(
        Number(item.merchant_id),
        selected.has(Number(item.merchant_id)),
      );
    shopCoupons.clear();
    slots.clear();
    for (const [merchant, id] of Object.entries(request.shopCoupons ?? {}))
      shopCoupons.set(Number(merchant), Number(id));
    for (const [merchant, id] of Object.entries(request.reservationSlots ?? {}))
      slots.set(Number(merchant), Number(id));
  }
  async function add(p) {
    requireSafetySaved();
    const old = cart.find(
      (c) => c.product_type === p.type && c.product_id === p.id,
    );
    await api("/cart", {
      method: "PUT",
      body: {
        productType: p.type,
        productId: p.id,
        quantity: (old?.quantity ?? 0) + 1,
        remark: old?.remark ?? "",
        expectedVersion: old?.version ?? null,
      },
    });
    invalidate();
    await loadCart();
  }
  async function loadCart() {
    const revision = ++cartRevision;
    const items = await api("/cart");
    if (revision !== cartRevision) return;
    cart = items;
    const count = cart.reduce((n, c) => n + c.quantity, 0) + " 件";
    $("#campusCartCount").textContent = count;
    $("#mobileCartShortcut").textContent = "购物车 · " + count;
    const el = document.createDocumentFragment();
    const groups = Map.groupBy(cart, (c) => c.merchant_id);
    for (const [m, items] of groups) {
      if (!selections.has(m)) selections.set(m, true);
      const c = card(items[0].merchant_name),
        checked = node("input", undefined, { type: "checkbox" });
      checked.checked = selections.get(m);
      checked.dataset.merchant = String(m);
      checked.addEventListener("change", () => {
        selections.set(m, checked.checked);
        invalidate();
      });
      c.append(field("本次结算", checked));
      const shop = getShops().find((s) => Number(s.id) === Number(m));
      const pickup = context().fulfillmentMode === "PICKUP";
      if (!shop) c.append(node("p", "当前校区或履约方式暂不可用", { class: "muted" }));
      else {
        const goods = items.reduce((sum, item) => sum + Math.round(Number(item.unit_price) * 100) * item.quantity, 0);
        const minimum = Math.round(Number(pickup ? shop.pickup_minimum_amount : shop.minimum_amount) * 100);
        const gap = minimum - goods;
        c.append(node("p", (gap > 0
          ? "距离" + (pickup ? "自提最低消费" : "起送") + "还差 " + money(gap / 100)
          : "已满足" + (pickup ? "自提最低消费" : "起送金额")) +
          (pickup ? "" : " · 本店配送费预计 " + money(shop.fee)), { class: "muted" }));
      }
      for (const item of items) {
        const p = node("div", undefined, { class: "cart-item" }),
          actions = node("span", undefined, { class: "quantity-actions" });
        p.append(
          node("span", item.product_name + " × " + item.quantity, {
            class: "cart-item-name",
          }),
        );
        actions.append(
          button("减少", async () => {
            if (item.quantity === 1)
              await api(
                "/cart/" + item.id + "?expectedVersion=" + item.version,
                { method: "DELETE" },
              );
            else
              await api("/cart", {
                method: "PUT",
                body: {
                  productType: item.product_type,
                  productId: item.product_id,
                  quantity: item.quantity - 1,
                  remark: item.remark,
                  expectedVersion: item.version,
                },
              });
            invalidate();
            await loadCart();
          }),
          button("增加", () =>
            add({ type: item.product_type, id: item.product_id }),
          ),
          button("移除", async () => {
            await api("/cart/" + item.id + "?expectedVersion=" + item.version, {
              method: "DELETE",
            });
            invalidate();
            await loadCart();
          }),
        );
        p.append(actions);
        c.append(p);
      }
      const coupon = select(couponChoices(m), shopCoupons.get(m) ?? "");
      coupon.addEventListener("change", () => {
        shopCoupons.set(m, coupon.value ? Number(coupon.value) : null);
        invalidate();
      });
      c.append(field("店铺券", coupon));
      const times = await api("/reservations/" + m),
        slot = select(
          [
            ["", "尽快送达 / 尽快自提"],
            ...times.map((s) => [
              s.id,
              s.starts_at +
                " 至 " +
                s.ends_at +
                " · 余" +
                (s.capacity - s.reserved_count),
            ]),
          ],
          slots.get(m) ?? "",
        );
      slot.addEventListener("change", () => {
        slots.set(m, slot.value ? Number(slot.value) : null);
        invalidate();
      });
      c.append(field("预约", slot));
      el.append(c);
    }
    if (!items.length)
      el.append(
        node("p", "购物车还是空的，先从左侧菜单选几样喜欢的。", {
          class: "empty-state",
        }),
      );
    if (revision === cartRevision) $("#campusCart").replaceChildren(el);
  }
  async function addCombination(q) {
    requireSafetySaved();
    const current = context();
    if (q.request.campusId !== current.campusId ||
        (q.request.addressId ?? null) !== current.addressId ||
        q.request.fulfillmentMode !== current.fulfillmentMode)
      throw Error("收货校区、地址或履约方式已改变，请重新查找组合。");
    const products = q.shops.flatMap((s) =>
      s.lines.map((l) => {
        const p = l.product,
          old = cart.find(
            (item) => item.product_type === p.type && item.product_id === p.id,
          );
        return {
          productVersion: p.version,
          item: {
            productType: p.type,
            productId: p.id,
            quantity: (old?.quantity ?? 0) + l.quantity,
            remark: old?.remark ?? "",
            expectedVersion: old?.version ?? null,
          },
        };
      }),
    );
    invalidate();
    let failure;
    try {
      await api("/campus-assistant/apply-recommendation", {
        method: "POST", body: { ...current, expiresAt: q.expiresAt, products },
      });
    } catch (error) {
      failure = error;
    }
    try {
      await loadCart();
    } catch {
      throw Error(
        "购物车状态尚未确认，请刷新页面核对后继续，不要直接重复加购。",
      );
    }
    if (failure)
      throw Error(
        failure.message +
          "。已刷新购物车；请求结果可能已提交，请核对后再操作。",
      );
    notice("组合已整组加入，请核对购物车并重新计算报价。");
  }

  return {
    reset() {
      cartRevision++;
      cart = [];
      selections.clear();
      shopCoupons.clear();
      slots.clear();
      $("#campusCart").replaceChildren();
      $("#campusCartCount").textContent = "0 件";
      $("#mobileCartShortcut").textContent = "购物车 · 0 件";
    },
    add,
    loadCart,
    addCombination,
    couponSelection,
    selectedMerchants,
    selectedReservationSlots,
    deselectMerchant,
    applyQuoteSelection,
    get items() {
      return cart;
    },
  };
}
