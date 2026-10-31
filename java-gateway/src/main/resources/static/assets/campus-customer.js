import { api, state, onForm, notice, run } from "./api.js";
import { initAuth } from "./auth.js";
import {
  node,
  button,
  money,
  labels,
  card,
  watchEvents,
} from "./commerce-ui.js";
import { createSafetyController, safetyHeaders } from "./campus-safety.js";
import { createCartController } from "./campus-cart.js";
import { createQuoteController } from "./campus-quote.js";
import { createOrdersController } from "./campus-orders.js";
import { createFulfillmentList } from "./fulfillment-list.js";
const $ = (s) => document.querySelector(s);
const feedbackList = createFulfillmentList("/feedback", "我的反馈", {
  initialScope: "all", allLabel: "全部反馈", activeLabel: "待回复",
});
let addresses = [],
  coupons = [],
  shops = [],
  menuRevision = 0,
  shopRevision = 0,
  navigationRevision = 0,
  shopCampus = null;

const safetyController = createSafetyController({
  $,
  getShops: () => shops,
  navigateToShop,
  invalidate: () => invalidate(),
  loadMenu,
  loadFavorites,
});
const {
  requireSafetySaved,
  renderSafety,
  resetSafety,
  saveSafety,
  reflectSafetyEdit,
} = safetyController;
const cartController = createCartController({
  $,
  requireSafetySaved,
  invalidate: () => invalidate(),
  couponChoices,
});
const { add, loadCart, addCombination, couponSelection } = cartController;
const quoteController = createQuoteController({
  $,
  getCart: () => cartController,
  getShops: () => shops,
  getCoupons: () => coupons,
  hasPendingSafety: () => safetyController.pending,
  requireSafetySaved,
  context,
  couponSelection,
  loadCart,
  refresh,
});
const { invalidate, price, applyAssistantQuote } = quoteController;
const { loadOrders } = createOrdersController({
  $,
  requireSafetySaved,
  invalidate,
  loadCart,
  refresh,
  loadFeedback,
});
async function navigateToShop(merchant, location) {
  let revision;
  if (
    $("#campus").value !== location.campusId ||
    shopCampus !== location.campusId
  ) {
    $("#campus").value = location.campusId;
    const navigation = changeCampus(merchant);
    revision = navigationRevision;
    await navigation;
  } else {
    revision = ++navigationRevision;
    shopRevision++;
    $("#shop").value = merchant;
    await loadMenu();
  }
  if (
    revision !== navigationRevision ||
    $("#campus").value !== location.campusId
  )
    return false;
  if (![...$("#shop").options].some((option) => option.value === merchant))
    throw Error(
      "该店已不在当前服务列表，可放弃本页未保存选择；已保存约束仍保留。",
    );
  return $("#shop").value === merchant;
}

function showMode() {
  const pickup = $("#mode").value === "PICKUP";
  $("#address").disabled = pickup;
  $("#modeHint").textContent = pickup
    ? "自提无需收货地址，仅支持店铺所属校区；当前规则仍需店铺开放该校区服务，并满足该店起送门槛。"
    : "一次结算使用同一收货地址和履约方式，配送费用按店铺分别计算。";
}
function context() {
  return {
    campusId: Number($("#campus").value),
    addressId:
      $("#mode").value === "DELIVERY" && $("#address").value
        ? Number($("#address").value)
        : null,
    fulfillmentMode: $("#mode").value,
  };
}
function couponChoices(merchant) {
  return [
    ["", "不使用优惠券"],
    ...coupons
      .filter(
        (c) =>
          c.status === "AVAILABLE" &&
          (merchant === null
            ? c.merchant_id === null
            : Number(c.merchant_id) === Number(merchant)),
      )
      .map((c) => [
        c.id,
        c.name + " · 满" + c.threshold_amount + "减" + c.discount_amount,
      ]),
  ];
}
async function loadAddresses(isCurrent = () => true) {
  const result = await api("/addresses");
  if (!isCurrent()) return;
  addresses = result;
  const old = $("#address").value;
  $("#address").replaceChildren(
    ...addresses
      .filter((a) => a.campus_id === Number($("#campus").value))
      .map((a) =>
        node("option", a.recipient_name + " · " + a.detail, { value: a.id }),
      ),
  );
  if ([...$("#address").options].some((o) => o.value === old))
    $("#address").value = old;
  $("#addresses").replaceChildren(
    ...addresses.map((a) => {
      const p = node("p", a.recipient_name + " · " + a.detail);
      p.append(
        button("删除地址", async () => {
          await api("/addresses/" + a.id, { method: "DELETE" });
          invalidate();
          await loadAddresses();
        }),
      );
      return p;
    }),
  );
}
async function loadShops(preferredMerchant, isCurrent = () => true) {
  const revision = ++shopRevision,
    campus = $("#campus").value,
    el = $("#campusMenu"),
    originalSelection = $("#shop").value;
  menuRevision++;
  resetSafety("正在加载店铺约束…");
  el.replaceChildren(
    node("p", "正在加载该校区店铺与菜单…", { class: "muted" }),
  );
  el.setAttribute("aria-busy", "true");
  let result;
  try {
    result = await api("/campuses/" + campus + "/merchants");
  } catch (error) {
    if (revision === shopRevision && campus === $("#campus").value) {
      el.replaceChildren(
        node("p", "店铺加载失败，请点击刷新重试。", { class: "error" }),
      );
      el.setAttribute("aria-busy", "false");
    }
    throw error;
  }
  if (
    revision !== shopRevision ||
    campus !== $("#campus").value ||
    !isCurrent()
  )
    return;
  const selected =
    preferredMerchant && $("#shop").value === originalSelection
      ? preferredMerchant
      : $("#shop").value;
  shops = result;
  shopCampus = campus;
  $("#shop").replaceChildren(
    ...shops.map((s) =>
      node("option", s.name + " · 配送" + money(s.fee), { value: s.id }),
    ),
  );
  if (shops.some((s) => String(s.id) === selected)) $("#shop").value = selected;
  await loadMenu();
}
async function loadMenu() {
  const revision = ++menuRevision,
    campus = $("#campus").value,
    merchant = $("#shop").value,
    el = $("#campusMenu");
  resetSafety(merchant ? "正在加载当前店铺约束…" : "请选择可服务店铺。");
  el.replaceChildren(node("p", "正在加载菜单…", { class: "muted" }));
  el.setAttribute("aria-busy", "true");
  if (shopCampus !== campus) return;
  if (!merchant) {
    el.replaceChildren(node("p", "该校区暂无可服务店铺。"));
    el.setAttribute("aria-busy", "false");
    return;
  }
  const current = () =>
    revision === menuRevision &&
    campus === $("#campus").value &&
    merchant === $("#shop").value;
  let result, safety;
  try {
    [result, safety] = await Promise.all([
      api("/campus-menu/" + merchant),
      api("/order/safety-context", { headers: safetyHeaders(merchant) }),
    ]);
  } catch (error) {
    if (current()) {
      resetSafety("约束或菜单加载失败，请点击刷新重试。");
      el.replaceChildren(
        node("p", "菜单加载失败，请点击刷新重试。", { class: "error" }),
      );
      el.setAttribute("aria-busy", "false");
    }
    throw error;
  }
  if (!current()) return;
  renderSafety(safety, merchant);
  el.replaceChildren();
  el.setAttribute("aria-busy", "false");
  for (const r of result) {
    const p = r.product,
      c = node("article", undefined, { class: "menu-product" }),
      copy = node("div", undefined, { class: "product-copy" }),
      actions = node("div", undefined, { class: "product-actions" });
    copy.append(
      node("h3", p.name),
      node("p", money(p.price), { class: "product-price" }),
      node("p", "包装 " + money(p.packagingFee) + " · 剩余 " + r.stock, {
        class: "product-meta",
      }),
    );
    const save = button("收藏", async () => {
      await api("/favorites/" + p.type + "/" + p.id, { method: "POST" });
      await loadFavorites();
    });
    save.className = "save-button";
    actions.append(
      button("加入购物车", () => add(p)),
      save,
    );
    c.append(copy, actions);
    el.append(c);
  }
  if (!el.children.length)
    el.append(
      node(
        "p",
        safety.needsClarification
          ? "请先在上方确认本店过敏约束，再查看安全可售商品。"
          : "暂无符合当前安全约束的可售商品。",
        { class: "empty-state" },
      ),
    );
  reflectSafetyEdit();
}
async function loadCoupons() {
  coupons = await api("/coupons/mine");
  const old = $("#platformCoupon").value;
  $("#platformCoupon").replaceChildren(
    ...couponChoices(null).map(([v, t]) => node("option", t, { value: v })),
  );
  if ([...$("#platformCoupon").options].some((o) => o.value === old))
    $("#platformCoupon").value = old;
  const campaigns = await api("/coupons"),
    el = $("#couponList");
  el.replaceChildren();
  for (const c of campaigns) {
    const owned = coupons.find((x) => x.campaign_id === c.id),
      box = card(c.name);
    box.append(
      node(
        "p",
        (c.merchant_id ? "店铺券" : "平台券") +
          " · 满" +
          c.threshold_amount +
          "减" +
          c.discount_amount,
      ),
    );
    if (owned) box.append(node("p", labels[owned.status] ?? owned.status));
    else
      box.append(
        button("领取", async () => {
          await api("/coupons/" + c.id + "/claim", { method: "POST" });
          invalidate();
          await loadCoupons();
          await loadCart();
        }),
      );
    el.append(box);
  }
  if (!campaigns.length)
    el.append(
      node("p", "当前暂无可领取活动。新的优惠开放后会显示在这里。", {
        class: "empty-state",
      }),
    );
}
async function loadFavorites(isCurrent = () => true) {
  const campus = $("#campus").value,
    el = node("div"),
    favorites = await api("/favorites");
  if (!isCurrent()) return;
  if (!favorites.length)
    el.append(
      node("p", "在菜单中收藏喜欢的商品，下次更快找到。", {
        class: "empty-state",
      }),
    );
  for (const f of favorites) {
    const products = await api(
      "/campus-assistant/search?campusId=" + campus + "&keyword=",
    );
    if (!isCurrent()) return;
    const p = products.find(
        (p) => p.type === f.product_type && p.id === f.product_id,
      ),
      c = node("p", p?.name ?? "收藏商品当前不可售或不在本校区服务范围");
    if (p) c.append(button("加入购物车", () => add(p)));
    c.append(
      button("取消收藏", async () => {
        await api("/favorites/" + f.product_type + "/" + f.product_id, {
          method: "DELETE",
        });
        await loadFavorites();
      }),
    );
    el.append(c);
  }
  if (isCurrent()) $("#favoriteList").replaceChildren(...el.childNodes);
}
async function loadFeedback() {
  const el = $("#feedbackList"),
    result = await feedbackList.read();
  if (!result.isCurrent()) return;
  const rows = result.rows;
  el.replaceChildren(
    feedbackList.controls(result, loadFeedback),
    ...rows.map((f) => node("p", f.message + " · " + (f.reply ?? "等待回复"))),
  );
  if (!rows.length)
    el.append(
      node("p", "暂无反馈记录。订单内可以提交反馈并查看回复。", {
        class: "empty-state",
      }),
    );
}
async function refresh() {
  await loadAddresses();
  await loadCoupons();
  await loadCart();
  if (shopCampus !== $("#campus").value) await loadShops();
  else await loadMenu();
  await loadOrders();
  await loadFavorites();
  await loadFeedback();
}
$("#saveCampusSafety").addEventListener("click", () =>
  run(() => saveSafety(), $("#saveCampusSafety")),
);
$("#clearCampusSafety").addEventListener("click", () =>
  run(() => saveSafety(true), $("#clearCampusSafety")),
);
$("#campusSafety").addEventListener("change", () =>
  safetyController.markEdited(),
);
async function changeCampus(preferredMerchant) {
  const revision = ++navigationRevision,
    campus = $("#campus").value,
    isCurrent = () =>
      revision === navigationRevision && campus === $("#campus").value;
  invalidate();
  shopRevision++;
  menuRevision++;
  shopCampus = null;
  resetSafety("正在切换校区…");
  $("#campusMenu").replaceChildren(
    node("p", "正在切换校区…", { class: "muted" }),
  );
  $("#campusMenu").setAttribute("aria-busy", "true");
  try {
    await loadAddresses(isCurrent);
    if (!isCurrent()) return;
    await loadShops(preferredMerchant, isCurrent);
    if (!isCurrent()) return;
    await loadFavorites(isCurrent);
    if (!isCurrent()) return;
  } catch (error) {
    if (isCurrent()) throw error;
  }
}
$("#quoteButton").addEventListener("click", () =>
  run(price, $("#quoteButton")),
);
$("#refresh").addEventListener("click", () => run(refresh));
$("#shop").addEventListener("change", () => {
  navigationRevision++;
  shopRevision++;
  run(shopCampus === $("#campus").value ? loadMenu : () => changeCampus());
});
$("#mode").addEventListener("change", showMode);
for (const id of ["address", "mode", "platformCoupon"])
  $("#" + id).addEventListener("change", invalidate);
$("#campus").addEventListener("change", () => run(changeCampus));
onForm("addressForm", async (d) => {
  await api("/addresses", {
    method: "POST",
    body: { ...d, campusId: context().campusId },
  });
  invalidate();
  await loadAddresses();
});
onForm("campusChat", async (d) => {
  requireSafetySaved();
  const el = $("#campusMessages"),
    revision = quoteController.revision;
  el.append(node("p", d.message, { class: "message message-user" }));
  const pending = node("p", "正在查询菜单与点餐信息…", {
    class: "message message-pending",
  });
  el.append(pending);
  el.setAttribute("aria-busy", "true");
  el.scrollTop = el.scrollHeight;
  try {
    const r = await api("/campus-assistant/chat", {
      method: "POST",
      body: { ...context(), message: d.message },
    });
    el.append(node("p", r.reply, { class: "message message-assistant" }));
    if (r.contextApplied)
      el.append(
        node("p", "已参考同一校区、地址与履约方式的近期对话。", {
          class: "conversation-context",
        }),
      );
    const changed = revision !== quoteController.revision;
    invalidate();
    const responseRevision = quoteController.revision;
    await loadCoupons();
    await loadCart();
    await loadMenu();
    const written = (r.toolCalls ?? []).filter(
      (call) => call.tool === "prepare_campus_cart" && call.status === "ok",
    );
    if (written.length) {
      const prepared = cartController.items.filter((item) =>
        written.some((call) => call.item?.productType === item.product_type &&
          call.item?.productId === item.product_id),
      );
      const review = node("div", undefined, { class: "message message-assistant" });
      if (prepared.length)
        review.append(node("p", "已同步购物车：" + prepared.map(
          (item) => item.merchant_name + " · " + item.product_name + " × " + item.quantity,
        ).join("；")));
      review.append(node("p", "请核对店铺、商品和目标总数量，再核对报价。创建订单与模拟支付仍需你亲自确认。"));
      review.append(button("核对购物车", () => {
        $("#cartSection").scrollIntoView({ behavior: "smooth", block: "start" });
        $("#quoteButton").focus({ preventScroll: true });
      }));
      el.append(review);
    }
    if (
      r.quote &&
      !changed &&
      responseRevision === quoteController.revision &&
      (await applyAssistantQuote(r.quote, responseRevision))
    )
      notice(
        "助手已准备实际报价，已同步所选店铺与优惠。请核对账单后自行确认。",
      );
    else if (r.quote)
      notice("购物车或收货条件已变化，助手报价未应用。请重新计算当前报价。");
  } catch (error) {
    // Java can record pending allergens before an unavailable Agent responds.
    // A failed chat must invalidate any earlier quote and synchronize that state.
    invalidate();
    const refreshed = await Promise.allSettled([loadCart(), loadMenu()]);
    if (refreshed.some((result) => result.status === "rejected"))
      throw Error(
        error.message +
          "。购物车或安全状态尚未确认，请刷新页面核对后继续，不要直接重复操作。",
      );
    throw Error(
      error.message +
        "。已同步购物车与安全状态，部分操作可能已完成，请核对后继续；若显示待确认，请逐店确认。",
    );
  } finally {
    pending.remove();
    el.removeAttribute("aria-busy");
    el.scrollTop = el.scrollHeight;
  }
});
onForm("recommendForm", async (d) => {
  const r = await api("/campus-assistant/recommend", {
    method: "POST",
    body: {
      ...context(),
      ...couponSelection(),
      budget: Number(d.budget),
      keywords: d.keywords
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean),
    },
  });
  const el = $("#recommendations");
  el.replaceChildren(node("p", r.message));
  for (const q of r.candidates) {
    const c = card("组合合计 " + money(q.total));
    for (const s of q.shops)
      c.append(
        node(
          "p",
          s.merchantName +
            "：" +
            s.lines.map((l) => l.product.name).join("、") +
            " · " +
            money(s.payable),
        ),
      );
    c.append(button("将本组合加入购物车", () => addCombination(q)));
    el.append(c);
  }
});
initAuth(async () => {
  const campuses = await api("/campuses");
  $("#campus").replaceChildren(
    ...campuses.map((c) => node("option", c.name, { value: c.id })),
  );
  await loadShops();
  await refresh();
  watchEvents("/me/events", "campus-events:" + state.me.userId, async () => {
    if ($("#checkoutList").contains(document.activeElement)) return false;
    return loadOrders({ preserveEditing: true });
  }).catch(() => {});
});
