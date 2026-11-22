import { api, state, onForm, notice, run, accountSignal } from "./api.js";
import { initAuth } from "./auth.js";
import {
  node,
  button,
  money,
  field,
  labels,
  card,
  watchEvents,
} from "./commerce-ui.js";
import { createSafetyController, safetyHeaders } from "./campus-safety.js";
import { createCartController } from "./campus-cart.js";
import { createQuoteController } from "./campus-quote.js";
import { createOrdersController } from "./campus-orders.js";
import { createFulfillmentList } from "./fulfillment-list.js";
import { appendProductImage } from "./integrations.js";
import { resumePayment } from "./payment-session.js";
const $ = (s) => document.querySelector(s);
const feedbackList = createFulfillmentList("/feedback", "我的反馈", {
  initialScope: "all",
  allLabel: "全部反馈",
  activeLabel: "待回复",
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
  getShops: () => shops,
  context,
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
const ordersController = createOrdersController({
  $,
  requireSafetySaved,
  invalidate,
  loadCart,
  refresh,
  loadFeedback,
});
const { loadOrders } = ordersController;
$("#checkoutScope").addEventListener("change", () => run(ordersController.changeScope));
$("#mobileCartShortcut").addEventListener("click", () => {
  $("#cartSection").scrollIntoView({ block: "start" });
  $("#quoteButton").focus({ preventScroll: true });
});
function clearPrivateWorkspace() {
  addresses = [];
  coupons = [];
  shops = [];
  shopCampus = null;
  navigationRevision++;
  menuRevision++;
  shopRevision++;
  for (const form of $("#workspace").querySelectorAll("form")) form.reset();
  for (const id of ["campusMessages", "recommendations", "addresses", "address", "couponList", "platformCoupon", "favoriteList", "feedbackList", "campusMenu"])
    $("#" + id).replaceChildren();
  $("#campusMessages").removeAttribute("aria-busy");
  cartController.reset();
  safetyController.reset();
  ordersController.reset();
  $("#checkoutPayment").replaceChildren();
  $("#shopComparison").replaceChildren();
  $("#campusSearchResults").replaceChildren();
  $("#guestBrowse").hidden = false;
  $("#openShopsOnly").checked = false;
  invalidate();
}
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

function rememberLocation() {
  if (state.me?.userId) {
    const { campusId, fulfillmentMode } = context();
    localStorage.setItem("campus-location:" + state.me.userId, campusId + ":" + fulfillmentMode);
  }
}

function showMode() {
  const pickup = $("#mode").value === "PICKUP";
  $("#address").disabled = pickup;
  $("#modeHint").textContent = pickup
    ? "自提无需收货地址，仅支持店铺所在校区，最低消费由店铺设置。"
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
          c.status === "AVAILABLE" && !c.availability_reason &&
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
  if (!$("#address").options.length)
    $("#address").append(node("option", "当前校区无收货地址，请添加", { value: "" }));
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
  const pickup = context().fulfillmentMode === "PICKUP";
  shops = pickup ? result.filter((s) => Number(s.campus_id) === Number(campus)) : result;
  const visibleShops = $("#openShopsOnly").checked
    ? shops.filter((s) => s.accepting_orders && s.open_now)
    : shops;
  shopCampus = campus;
  $("#shopComparison").replaceChildren(...visibleShops.map((s) => {
    const choice = button(s.name, async () => {
      $("#shop").value = String(s.id);
      navigationRevision++;
      await loadMenu();
    });
    const opening = s.accepting_orders ? (s.open_now ? "营业中" : "当前休息") : "暂停接单";
    const terms = pickup
      ? "自提最低消费 " + money(s.pickup_minimum_amount)
      : "起送 " + money(s.minimum_amount) + " · 配送 " + money(s.fee);
    const box = node("div", undefined, { class: "shop-summary" });
    box.append(choice, node("p", opening + " · " + terms));
    const min = pickup ? s.preparation_min ?? s.eta_min : s.eta_min;
    const max = pickup ? s.preparation_max ?? s.eta_max : s.eta_max;
    box.append(node("p", "预计" + (pickup ? "可取餐 " : "送达 ") + min + "–" + max + " 分钟"));
    return box;
  }));
  if (!visibleShops.length) $("#shopComparison").append(
    node("p", "当前筛选没有店铺，请调整营业筛选、校区或履约方式。"),
  );
  if (visibleShops.length) $("#shopComparison").append(
    node("p", "时间按店铺配置估算，费用与可用情况以当前报价为准。", { class: "muted" }),
  );
  $("#shop").replaceChildren(
    ...visibleShops.map((s) => node("option", s.name, { value: s.id })),
  );
  if (visibleShops.some((s) => String(s.id) === selected)) $("#shop").value = selected;
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
    if (p.dishId)
      appendProductImage(c, p.dishId, p.name).catch(() => c.append(node("small", "图片暂不可用")));
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
  const displayed = new Set(campaigns.map((c) => c.id));
  el.replaceChildren();
  for (const coupon of coupons.filter((c) => !displayed.has(c.campaign_id))) {
    const box = card(coupon.name);
    box.append(node("p", coupon.availability_reason ?? labels[coupon.status] ?? coupon.status));
    el.append(box);
  }
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
    if (owned) box.append(node("p", owned.availability_reason ?? labels[owned.status] ?? owned.status));
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
  await loadShops();
  await loadCart();
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
    await loadCart();
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
$("#mode").addEventListener("change", () => {
  showMode();
  rememberLocation();
  $("#campusSearchResults").replaceChildren();
  run(() => changeCampus());
});
$("#openShopsOnly").addEventListener("change", () => run(() => loadShops()));
onForm("campusMenuSearch", async ({ keyword }) => {
  const current = context();
  const revision = navigationRevision;
  const results = $("#campusSearchResults");
  const query = new URLSearchParams({ keyword: keyword.trim() });
  const rows = await api("/campus-browse/campuses/" + current.campusId + "/menu?" + query);
  if (revision !== navigationRevision || current.campusId !== context().campusId) return;
  const list = rows.filter((row) => shops.some((shop) => Number(shop.id) === Number(row.merchant_id)));
  results.replaceChildren(...list.map((row) => {
    const shop = shops.find((s) => Number(s.id) === Number(row.merchant_id));
    const status = !row.available ? "暂不可售"
      : !shop.accepting_orders || !shop.open_now ? "店铺当前未接单" : "当前可浏览";
    const entry = node("article");
    entry.append(node("p", row.name + " · " + row.merchant_name + " · " + money(row.price) + " · " + status));
    entry.append(button("查看店内菜单", async () => {
      $("#openShopsOnly").checked = false;
      await loadShops(String(row.merchant_id));
      $("#shop").focus();
    }));
    return entry;
  }));
  if (!list.length) results.append(node("p", "当前校区与履约方式下没有匹配的菜品。"));
});
for (const id of ["address", "mode", "platformCoupon"])
  $("#" + id).addEventListener("change", invalidate);
$("#campus").addEventListener("change", () => {
  rememberLocation();
  $("#campusSearchResults").replaceChildren();
  run(() => changeCampus());
});
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
  const lifetime = accountSignal();
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
    if (r.recommendation && !changed) {
      const results = node("div", undefined, { class: "message message-assistant" });
      renderRecommendations(results, r.recommendation);
      el.append(results);
    }
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
        written.some(
          (call) =>
            call.item?.productType === item.product_type &&
            call.item?.productId === item.product_id,
        ),
      );
      const review = node("div", undefined, {
        class: "message message-assistant",
      });
      if (prepared.length)
        review.append(
          node(
            "p",
            "已同步购物车：" +
              prepared
                .map(
                  (item) =>
                    item.merchant_name +
                    " · " +
                    item.product_name +
                    " × " +
                    item.quantity,
                )
                .join("；"),
          ),
        );
      review.append(
        node(
          "p",
          "请核对店铺、商品和目标总数量，再核对报价。创建订单与模拟支付仍需你亲自确认。",
        ),
      );
      review.append(
        button("核对购物车", () => {
          $("#cartSection").scrollIntoView({
            behavior: "smooth",
            block: "start",
          });
          $("#quoteButton").focus({ preventScroll: true });
        }),
      );
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
    if (lifetime.aborted) return;
    el.append(button("返回菜单继续选餐", () => {
      $("#browse").scrollIntoView({ block: "start" });
      $("#shop").focus({ preventScroll: true });
    }));
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
    if (!lifetime.aborted) {
      el.removeAttribute("aria-busy");
      el.scrollTop = el.scrollHeight;
    }
  }
});
onForm("recommendForm", async (d) => {
  const r = await api("/campus-assistant/recommend", {
    method: "POST",
    body: {
      ...context(),
      ...couponSelection(),
      budget: Number(d.budget),
      preferSameShop: d.preferSameShop === "on",
      preferFastest: d.preferFastest === "on",
      keywords: d.keywords
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean),
    },
  });
  renderRecommendations($("#recommendations"), r);
});
function renderRecommendations(el, result) {
  el.replaceChildren(node("p", result.message));
  const fastest = (q) => Math.max(...q.shops.map((shop) => shop.etaMax));
  const minimum = Math.min(...result.candidates.map((q) => Number(q.total)));
  const quickest = Math.min(...result.candidates.map(fastest));
  for (const q of result.candidates) {
    const summary = card("预计实付 " + money(q.total) + (q.shops.length === 1 ? " · 同一家店" : " · " + q.shops.length + " 家店"));
    if (result.candidates.length > 1) {
      const traits = [];
      if (Number(q.total) === minimum) traits.push("所列方案中实付最低");
      if (fastest(q) === quickest) traits.push("所列方案中预计最快");
      if (traits.length) summary.append(node("p", traits.join(" · "), { class: "muted" }));
    }
    for (const shop of q.shops) {
      summary.append(node("p", shop.merchantName + " · " + money(shop.payable) +
        " · 预计 " + shop.etaMin + "–" + shop.etaMax + " 分钟"));
      const items = node("ul");
      for (const line of shop.lines)
        items.append(node("li", line.product.name + " × " + line.quantity));
      summary.append(items);
    }
    summary.append(button("将本组合加入购物车", async () => {
      try {
        await addCombination(q);
      } catch (error) {
        if (!summary.querySelector("[data-retry-recommendation]")) {
          const adjust = button("重新搜索组合", () => $("#recommendForm [name=budget]").focus());
          adjust.dataset.retryRecommendation = "";
          summary.append(adjust);
        }
        throw error;
      }
    }));
    el.append(summary);
  }
  if (result.candidates.length) return;
  for (const issue of result.issues ?? []) el.append(node("p", issue, { class: "muted" }));
  for (const [label, selector] of [
    ["调整预算", "#recommendForm [name=budget]"],
    ["替换品类", "#recommendForm [name=keywords]"],
    ["调整履约条件", "#mode"],
  ])
    el.append(button(label, () => $(selector).focus()));
}
$("#guestBrowse").addEventListener("toggle", () => {
  const guest = $("#guestBrowse"), view = $("#guestCatalog");
  if (!guest.open || state.me || view.childElementCount) return;
  run(async () => {
    const campuses = await api("/campus-browse/campuses");
    if (state.me) return;
    const campus = node("select"), shop = node("select"), menu = node("div");
    campus.replaceChildren(...campuses.map((c) => node("option", c.name, { value: c.id })));
    let revision = 0;
    async function readMenu() {
      if (!shop.value) return;
      const current = ++revision;
      const rows = await api("/campus-browse/campuses/" + campus.value + "/menu?merchant=" + shop.value);
      if (state.me || current !== revision) return;
      menu.replaceChildren(...rows.map((item) => node("p", item.name + " · " + money(item.price) +
        (item.available ? "" : " · 暂不可售"))));
      if (!rows.length) menu.append(node("p", "该店暂未发布菜品。"));
    }
    async function readShops() {
      const current = ++revision;
      if (!campus.value) return;
      sessionStorage.setItem("campus-guest-campus", campus.value);
      const rows = await api("/campus-browse/campuses/" + campus.value + "/shops");
      if (state.me || current !== revision) return;
      shop.replaceChildren(...rows.map((s) => node("option",
        s.name + (s.open_now && s.accepting_orders ? "" : " · 当前未接单"), { value: s.id })));
      if (rows.length) await readMenu();
      else menu.replaceChildren(node("p", "当前校区暂无店铺。"));
    }
    campus.addEventListener("change", () => run(readShops, campus));
    shop.addEventListener("change", () => run(readMenu, shop));
    view.append(field("浏览校区", campus), field("浏览店铺", shop), menu,
      button("登录后点餐", () => $("#login input").focus()));
    await readShops();
  });
});

initAuth(async () => {
  accountSignal().addEventListener("abort", clearPrivateWorkspace, { once: true });
  $("#guestBrowse").hidden = true;
  $("#guestBrowse").open = false;
  $("#guestCatalog").replaceChildren();
  const campuses = await api("/campuses");
  $("#campus").replaceChildren(
    ...campuses.map((c) => node("option", c.name, { value: c.id })),
  );
  const [previousCampus, previousMode] =
    (localStorage.getItem("campus-location:" + state.me.userId) ?? "").split(":");
  if (campuses.some((campus) => String(campus.id) === previousCampus)) {
    $("#campus").value = previousCampus;
    $("#mode").value = previousMode === "PICKUP" ? "PICKUP" : "DELIVERY";
  } else if (campuses.some((campus) => String(campus.id) === sessionStorage.getItem("campus-guest-campus"))) {
    $("#campus").value = sessionStorage.getItem("campus-guest-campus");
  }
  showMode();
  await refresh();
  quoteController.offerConfirmationRecovery();
  watchEvents("/me/events", "campus-events:" + state.me.userId, async () => {
    if ($("#checkoutList").contains(document.activeElement)) return false;
    return loadOrders({ preserveEditing: true });
  }).catch(() => {});
  await resumePayment(refresh);
});
