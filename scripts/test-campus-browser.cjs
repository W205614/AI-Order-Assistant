// Four-role campus acceptance. Fixtures are restricted to disposable local test ports.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const options = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, all) => {
    if (v.startsWith("--")) a.push([v.slice(2), all[i + 1]]);
    return a;
  }, []),
);
const base = options["base-url"] || "http://127.0.0.1:19090";
const url = new URL(base);
if (
  !["127.0.0.1", "localhost"].includes(url.hostname) ||
  !["19090", "19092", "19093"].includes(url.port)
)
  throw Error("Disposable local test ports only");
const { chromium } = require(options["playwright-module"] || "playwright");
const env = Object.fromEntries(
  fs
    .readFileSync(options["env-file"] || ".github/compose-ci.env", "utf8")
    .split(/\r?\n/)
    .filter((l) => l && !l.startsWith("#") && l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const output = options.report || "load/results/campus-browser.json",
  checks = [],
  errors = [];
function check(name, test) {
  assert.ok(test, name);
  checks.push(name);
  console.log("Passed: " + name);
}
async function request(
  context,
  route,
  method = "GET",
  data,
  management = false,
  expected = 200,
) {
  const headers = management ? { "X-Session-Type": "management" } : {};
  if (route === "/checkouts/confirm")
    headers["Idempotency-Key"] =
      "browser-" + require("node:crypto").randomUUID();
  if (method !== "GET") {
    const r = await context.request.get(base + "/auth/csrf");
    headers["X-XSRF-TOKEN"] = (await r.json()).data.token;
  }
  const response = await context.request.fetch(base + route, {
    method,
    data,
    headers,
  });
  const body = await response.json();
  assert.equal(response.status(), expected, route + ": " + body.msg);
  if (expected === 200) {
    assert.equal(body.code, 1, route + ": " + body.msg);
    return body.data;
  }
  return body;
}
async function clickMutation(page, locator, route, method = "POST", expected = 200) {
  const [r] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(route) && r.request().method() === method,
    ),
    locator.click(),
  ]);
  const body = await r.json();
  assert.equal(r.status(), expected, route + ": " + body.msg);
  if (expected === 200) {
    assert.equal(body.code, 1);
    return body.data;
  }
  return body;
}
async function open(context, route) {
  const page = await context.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base + route);
  await page.locator("#workspace").waitFor({ state: "visible" });
  return page;
}
const local = (minutes) =>
  new Date(Date.now() + (minutes + 480) * 60000).toISOString().slice(0, 19);

async function verifyRecommendationRequotes(
  browser, { suffix, password, east, stores, owners, products, platformCampaign },
) {
  const customer = await browser.newContext();
  try {
    await request(customer, "/auth/register", "POST", {
      username: "campus_requote_" + suffix, password, nickname: "重新报价验收",
    });
    const address = await request(customer, "/addresses", "POST", {
      campusId: east, recipientName: "验收同学", phone: "13800000000", detail: "东校区验收地址",
    });
    await request(customer, "/coupons/" + platformCampaign + "/claim", "POST");
    const platformCoupon = (await request(customer, "/coupons/mine"))
      .find((coupon) => coupon.campaign_id === platformCampaign);
    const page = await open(customer, "/campus/");
    const quote = page.locator("#quote");
    const quoteButton = page.locator("#quoteButton");
    const confirm = quote.getByRole("button", { name: "确认本次报价并创建订单" });
    const checkouts = () => request(customer, "/checkouts?scope=all");
    const [rice, tea] = products;
    const [riceShop, teaShop] = stores;
    const teaOwner = owners[1];
    const twoShops = [riceShop.id, teaShop.id];

    const controlledModel = options["controlled-model"] === "true";
    async function recommend() {
      const cards = page.locator(controlledModel ? "#campusMessages" : "#recommendations");
      if (controlledModel) {
        await page.locator("#campusPrompt").fill("验收推荐" + suffix);
        const result = await clickMutation(page, page.locator("#campusChat").getByRole("button", { name: "发送" }),
          "/campus-assistant/chat");
        assert.equal(result.completionSource, "java_recommendation");
        await page.locator("#campusMessages:not([aria-busy])").waitFor();
      } else {
        const form = page.locator("#recommendForm");
        await form.locator("[name=budget]").fill("50");
        await form.locator("[name=keywords]").fill("米饭" + suffix + ",奶茶" + suffix);
        await clickMutation(page, form.getByRole("button", { name: "查找组合" }), "/campus-assistant/recommend");
        await form.locator("button:enabled").waitFor();
      }
      const card = cards.locator("article").filter({ hasText: rice.name }).filter({ hasText: tea.name }).last();
      const add = card.getByRole("button", { name: "将本组合加入购物车" });
      await add.waitFor();
      return { card, add };
    }

    const cases = [
      {
        name: "sold_out", reason: /暂时售罄/, total: 13,
        change: async (changed) => {
          const sku = (await request(teaOwner, "/admin/skus", "GET", undefined, true)).find((row) => row.id === tea.id);
          await request(teaOwner, "/admin/skus/" + tea.id + "/campus-availability", "PUT",
            { expectedVersion: sku.version, soldOut: changed }, true);
        },
      },
      {
        name: "price_increase", reason: /商品已变化/, total: 35,
        notice: "「" + tea.name + "」单价已从 ¥15.00 变为 ¥20.00，请重新查找组合。",
        change: async (changed) => {
          const dish = (await request(teaOwner, "/admin/dishes", "GET", undefined, true)).find((row) => row.id === tea.dishId);
          await request(teaOwner, "/admin/dishes/" + dish.id, "PUT", { ...dish, price: changed ? 20 : 15 }, true);
        },
      },
      {
        name: "shop_stops_accepting", reason: /已暂停接单/, total: 13,
        notice: "「" + teaShop.name + "」已暂停接单，请重新查找组合",
        change: async (changed) => {
          const shop = await request(teaOwner, "/admin/merchant", "GET", undefined, true);
          await request(teaOwner, "/admin/merchant", "PUT",
            { ...shop, acceptingOrders: !changed, expectedVersion: shop.version }, true);
        },
      },
    ];

    for (const scenario of cases) {
      await page.reload();
      await page.locator("#workspace").waitFor({ state: "visible" });
      await page.locator("#campus").selectOption(String(east));
      await page.locator("#address").selectOption(String(address));
      const previousCheckouts = await checkouts();
      const staleRecommendation = await recommend();
      await scenario.change(true);
      const rejected = await clickMutation(page, staleRecommendation.add, "/campus-assistant/apply-recommendation", "POST", 400);
      assert.match(rejected.msg, scenario.reason);
      await staleRecommendation.card.getByRole("button", { name: "重新搜索组合" }).waitFor();
      const notice = page.locator("#notice").filter({ hasText: scenario.notice ?? scenario.reason });
      await notice.waitFor();
      if (scenario.notice) assert.equal(await notice.innerText(), scenario.notice);
      assert.deepEqual(await checkouts(), previousCheckouts);
      check(scenario.name + "_after_recommendation_rejects_entire_stale_combination",
        (await request(customer, "/cart")).length === 0);

      await scenario.change(false);
      const freshRecommendation = await recommend();
      await clickMutation(page, freshRecommendation.add, "/campus-assistant/apply-recommendation");
      await page.locator("#campusCart").getByText(tea.name + " × 1", { exact: true }).waitFor();
      const stopsAccepting = scenario.name === "shop_stops_accepting";
      if (stopsAccepting)
        await page.locator("#platformCoupon").selectOption(String(platformCoupon.id));
      const oldQuote = await clickMutation(page, quoteButton, "/checkouts/quotes");
      assert.deepEqual(oldQuote.shops.map((shop) => shop.merchantId), twoShops);
      assert.equal(Number(oldQuote.total), stopsAccepting ? 25 : 30);

      await scenario.change(true);
      const changedCart = await request(customer, "/cart");
      const conflict = await clickMutation(page, confirm, "/checkouts/confirm", "POST", 409);
      assert.equal(conflict.errorCode, "REQUOTE_REQUIRED");
      await quote.getByRole("button", { name: "重新计算并核对报价" }).waitFor();
      assert.deepEqual(await request(customer, "/cart"), changedCart);
      assert.deepEqual(await checkouts(), previousCheckouts);
      check(scenario.name + "_after_quote_blocks_stale_confirmation", await confirm.count() === 0);

      let freshQuote;
      const expectedShops = scenario.name === "price_increase" ? twoShops : [riceShop.id];
      if (scenario.name === "price_increase") {
        await quote.getByText("原实付 ¥30.00，当前实付 ¥35.00", { exact: true }).waitFor();
        await quote.getByText(/¥15\.00 → ¥20\.00/).waitFor();
        freshQuote = await clickMutation(page, quote.getByRole("button", { name: "重新计算并核对报价" }),
          "/checkouts/quotes");
      } else if (scenario.name === "sold_out") {
        await quote.getByText(/奶茶.*暂时售罄/).waitFor();
        await quote.getByRole("button", { name: "返回修改购物车" }).click();
        await clickMutation(page, page.locator(".cart-item").filter({ hasText: tea.name })
          .getByRole("button", { name: "移除" }), "/cart/" + changedCart.find((item) => item.product_id === tea.id).id, "DELETE");
        await page.locator("#campusCart").getByText(tea.name + " × 1", { exact: true }).waitFor({ state: "detached" });
        freshQuote = await clickMutation(page, quoteButton, "/checkouts/quotes");
      } else {
        const reason = conflict.data.unavailable[teaShop.id];
        assert.match(reason, /暂不接单/);
        await quote.getByText(teaShop.name + " · " + reason, { exact: true }).waitFor();
        const partialQuote = await clickMutation(
          page, quote.getByRole("button", { name: "重新计算并核对报价" }), "/checkouts/quotes",
        );
        assert.match(partialQuote.unavailable[0], /未达到优惠门槛/);
        await quote.getByText(/未达到优惠门槛/).waitFor();
        const subset = quote.getByRole("button", { name: "仅选择可提交店铺，重新计算报价" });
        await subset.waitFor();
        assert.equal(await confirm.count(), 0);
        freshQuote = await clickMutation(page, subset, "/checkouts/quotes");
        assert.deepEqual(await request(customer, "/cart"), changedCart);
      }
      assert.notEqual(freshQuote.id, oldQuote.id);
      assert.deepEqual(freshQuote.shops.map((shop) => shop.merchantId), expectedShops);
      assert.deepEqual(freshQuote.unavailable, {});
      assert.equal(Number(freshQuote.total), scenario.total);
      await quote.getByText("本次合计 ¥" + scenario.total.toFixed(2), { exact: true }).waitFor();
      assert.deepEqual(await checkouts(), previousCheckouts);
      const checkout = await clickMutation(page, confirm, "/checkouts/confirm");
      const detail = await request(customer, "/checkouts/" + checkout.id);
      assert.deepEqual(detail.orders.map((order) => order.merchant_id).sort((a, b) => a - b), expectedShops);
      check(scenario.name + "_creates_order_only_after_fresh_quote_confirmation",
        Number(checkout.total_amount) === scenario.total && (await checkouts()).length === previousCheckouts.length + 1);
      await page.locator("#checkoutPayment").getByRole("button", { name: "统一模拟支付" }).waitFor();
      await request(customer, "/checkouts/" + checkout.id + "/close", "POST");
      await scenario.change(false);
      for (const item of await request(customer, "/cart"))
        await request(customer, "/cart/" + item.id + "?expectedVersion=" + item.version, "DELETE");
    }
  } finally {
    await customer.close();
  }
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(options["browser-path"]
      ? { executablePath: options["browser-path"] }
      : {}),
  });
  try {
    const suffix = Date.now().toString(36),
      password = "Campus-browser-" + suffix;
    const platform = await browser.newContext();
    await request(
      platform,
      "/admin/login",
      "POST",
      {
        username: env.PLATFORM_ADMIN_USERNAME,
        password: env.PLATFORM_ADMIN_PASSWORD,
      },
      true,
    );
    const east = await request(
      platform,
      "/platform/campuses",
      "POST",
      { name: "验收东校区" + suffix },
      true,
    );
    const stores = [],
      owners = [],
      products = [];
    for (let i = 0; i < 3; i++) {
      const m = await request(
        platform,
        "/platform/merchants",
        "POST",
        {
          name: "校园店" + i + " " + suffix,
          ownerUsername: "campus_owner" + i + "_" + suffix,
          ownerPassword: password,
        },
        true,
      );
      stores.push(m);
      const owner = await browser.newContext();
      await request(
        owner,
        "/admin/login",
        "POST",
        { username: "campus_owner" + i + "_" + suffix, password },
        true,
      );
      owners.push(owner);
      await request(owner, "/admin/pickup-point", "PUT",
        { pickupPoint: "食堂东门取餐窗 " + i }, true);
      await request(
        owner,
        "/admin/delivery-rules",
        "PUT",
        {
          campusId: east,
          enabled: i !== 2,
          fee: i === 0 ? 3 : 2,
          minimumAmount: 0,
          etaMin: 20,
          etaMax: 60,
          expectedVersion: 0,
        },
        true,
      );
      await request(
        owner,
        "/admin/dishes",
        "POST",
        {
          name: ["米饭", "奶茶", "面条"][i] + suffix,
          category: "验收",
          price: [10, 15, 8][i],
          stock: 20,
          status: 1,
          allergens: "",
          allergenReviewed: true,
        },
        true,
      );
    }
    const guest = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const guestPage = await guest.newPage();
    await guestPage.goto(base + "/campus/");
    await guestPage.locator("#guestBrowse summary").click();
    await guestPage.getByLabel("浏览校区").selectOption(String(east));
    await guestPage.getByLabel("浏览店铺").selectOption(String(stores[0].id));
    await guestPage.locator("#guestCatalog").getByText("米饭" + suffix, { exact: false }).waitFor();
    check("guest_browses_published_menu_before_login",
      (await guestPage.locator("#guestCatalog").getByRole("button", { name: "加入购物车" }).count()) === 0);
    await guest.close();

    const customer = await browser.newContext();
    await request(customer, "/auth/register", "POST", {
      username: "campus_customer_" + suffix,
      password,
      nickname: "校园验收",
    });
    const address = await request(customer, "/addresses", "POST", {
      campusId: east,
      recipientName: "验收同学",
      phone: "13800000000",
      detail: "测试楼 · 东校门交接点",
    });
    for (const m of stores)
      products.push(
        (await request(customer, "/campus-menu/" + m.id))[0].product,
      );
    const campaign = (name) => ({
      name,
      thresholdAmount: 20,
      discountAmount: 3,
      totalCount: 10,
      claimStart: local(-60),
      claimEnd: local(60),
      validStart: local(-60),
      validEnd: local(120),
    });
    const shopCampaign = await request(
      owners[0],
      "/admin/coupons",
      "POST",
      campaign("店铺满减" + suffix),
      true,
    );
    const platformCampaign = await request(
      platform,
      "/platform/coupons",
      "POST",
      { ...campaign("平台满减" + suffix), discountAmount: 5 },
      true,
    );
    for (let i = 0; i < 2; i++)
      await request(
        platform,
        "/platform/riders",
        "POST",
        {
          username: "campus_rider" + i + "_" + suffix,
          password,
          campusIds: [east],
        },
        true,
      );
    check(
      "two_campuses_three_shops_two_riders_configured",
      east > 1 && stores.length === 3,
    );
    const page = await open(customer, "/campus/");
    await page.locator("#campus").selectOption(String(east));
    await page
      .locator("#shop option")
      .filter({ hasText: stores[0].name })
      .waitFor({ state: "attached" });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator("#mobileCartShortcut").waitFor({ state: "visible" });
    await page.locator("#shopComparison").getByText("配送 ¥3.00", { exact: false }).waitFor();
    check("mobile_can_compare_store_fees_and_jump_to_cart",
      (await page.locator("#shopComparison").innerText()).includes("配送 ¥3.00"));
    await page.locator("#mobileCartShortcut").click();
    assert.equal(await page.locator("#quoteButton").evaluate((el) => el === document.activeElement), true);
    await page.locator("#campusMenuSearch input").fill("奶茶" + suffix);
    await page.locator("#campusMenuSearch button").click();
    await page.locator("#campusSearchResults").getByText(stores[1].name, { exact: false }).waitFor();
    check("campus_search_finds_dishes_across_shops", true);
    for (let i = 0; i < 2; i++) {
      await page.locator("#shop").selectOption(String(stores[i].id));
      await page
        .locator("#campusMenu h3")
        .filter({ hasText: products[i].name })
        .waitFor();
      await clickMutation(
        page,
        page.locator("#campusMenu").getByRole("button", { name: "加入购物车" }),
        "/cart",
        "PUT",
      );
      await page
        .locator("#campusCart")
        .getByRole("heading", { name: stores[i].name, exact: true })
        .waitFor();
    }
    let cart = await request(customer, "/cart");
    await request(customer, "/cart", "PUT", {
      productType: products[0].type,
      productId: products[0].id,
      quantity: 3,
      remark: "",
      expectedVersion: cart.find((c) => c.merchant_id === stores[0].id).version,
    });
    await request(customer, "/cart", "PUT", {
      productType: products[2].type,
      productId: products[2].id,
      quantity: 1,
      remark: "",
    });
    await page.reload();
    await page.locator("#workspace").waitFor({ state: "visible" });
    await page.locator("#campus").selectOption(String(east));
    await page.locator("#address").selectOption(String(address));
    check(
      "cart_survives_switching_and_reload",
      (await request(customer, "/cart")).length === 3,
    );
    await clickMutation(
      page,
      page
        .locator("#couponList article")
        .filter({ hasText: "店铺满减" + suffix })
        .getByRole("button", { name: "领取" }),
      "/coupons/" + shopCampaign + "/claim",
    );
    await clickMutation(
      page,
      page
        .locator("#couponList article")
        .filter({ hasText: "平台满减" + suffix })
        .getByRole("button", { name: "领取" }),
      "/coupons/" + platformCampaign + "/claim",
    );
    const owned = await request(customer, "/coupons/mine"),
      shopCoupon = owned.find((c) => c.campaign_id === shopCampaign),
      platformCoupon = owned.find((c) => c.campaign_id === platformCampaign);
    await page
      .locator("#campusCart article")
      .filter({
        has: page.getByRole("heading", { name: stores[0].name, exact: true }),
      })
      .locator("select")
      .first()
      .selectOption(String(shopCoupon.id));
    await page
      .locator("#platformCoupon")
      .selectOption(String(platformCoupon.id));
    let quote = await clickMutation(
      page,
      page.locator("#quoteButton"),
      "/checkouts/quotes",
    );
    check(
      "unavailable_shop_is_reported",
      Object.keys(quote.unavailable).includes(String(stores[2].id)),
    );
    quote = await clickMutation(
      page,
      page.getByRole("button", { name: "仅选择可提交店铺，重新计算报价" }),
      "/checkouts/quotes",
    );
    check(
      "selected_subset_is_requoted_with_stacked_coupons",
      quote.shops.length === 2 && Number(quote.total) === 42,
    );
    const deliveryRule = (await request(owners[0], "/admin/delivery-rules", "GET", undefined, true))
      .find((rule) => rule.campus_id === east);
    const updateFee = (fee, expectedVersion) =>
      request(owners[0], "/admin/delivery-rules", "PUT", {
        campusId: east,
        enabled: true,
        fee,
        minimumAmount: deliveryRule.minimum_amount,
        etaMin: deliveryRule.eta_min,
        etaMax: deliveryRule.eta_max,
        expectedVersion,
      }, true);
    await updateFee(4, deliveryRule.version);
    const [staleConfirm] = await Promise.all([
      page.waitForResponse((response) =>
        new URL(response.url()).pathname === "/checkouts/confirm"),
      page.getByRole("button", { name: "确认本次报价并创建订单" }).click(),
    ]);
    assert.equal(staleConfirm.status(), 409);
    await page.locator("#quote").getByText("原实付 ¥42.00，当前实付 ¥43.00").waitFor();
    check("changed_quote_keeps_cart_and_explains_total_difference",
      (await request(customer, "/cart")).length === 3);
    await updateFee(3, deliveryRule.version + 1);
    await clickMutation(page,
      page.getByRole("button", { name: "重新计算并核对报价" }),
      "/checkouts/quotes");
    let original;
    let originalKey;
    const confirmPath = base + "/checkouts/confirm";
    let markLost;
    const lost = new Promise((resolve) => { markLost = resolve; });
    await page.route(confirmPath, async (route) => {
      originalKey = route.request().headers()["idempotency-key"];
      const response = await route.fetch();
      const body = await response.json();
      if (response.status() === 200) original = body.data;
      await route.abort("connectionreset");
      markLost();
    }, { times: 1 });
    await page.getByRole("button", { name: "确认本次报价并创建订单" }).click();
    await lost;
    assert.ok(original?.id);
    await page.reload();
    await page.locator("#workspace").waitFor({ state: "visible" });
    const recoveredHeader = page.waitForRequest((request) => request.url() === confirmPath);
    const checkout = await clickMutation(
      page, page.getByRole("button", { name: "核对上次订单确认" }), "/checkouts/confirm");
    check("confirm_response_loss_recovers_same_order_and_key_after_reload",
      checkout.id === original.id && (await recoveredHeader).headers()["idempotency-key"] === originalKey);
    const inlinePayment = page.locator("#checkoutPayment");
    await inlinePayment.getByRole("button", { name: "统一模拟支付" }).waitFor();
    check("inline_payment_shows_amount_and_countdown",
      (await inlinePayment.innerText()).includes("应付 ¥42.00") &&
      (await inlinePayment.innerText()).includes("剩余"));
    await clickMutation(
      page,
      inlinePayment.getByRole("button", { name: "统一模拟支付" }),
      "/checkouts/" + checkout.id + "/pay",
    );
    check(
      "unavailable_shop_remains_in_cart",
      (await request(customer, "/cart")).some(
        (c) => c.merchant_id === stores[2].id,
      ),
    );
    const detail = await request(customer, "/checkouts/" + checkout.id),
      order = detail.orders.find((o) => o.merchant_id === stores[0].id),
      order2 = detail.orders.find((o) => o.merchant_id === stores[1].id);
    const legacyCustomer = await open(customer, "/chat/");
    await legacyCustomer
      .locator("#merchant")
      .selectOption(String(stores[0].id));
    await legacyCustomer
      .locator("#orders a")
      .filter({ hasText: "前往校园结算管理此订单" })
      .waitFor();
    check(
      "legacy_customer_links_campus_orders_to_aggregate_management",
      (await legacyCustomer
        .locator("#orders")
        .getByRole("button", { name: "取消订单", exact: true })
        .count()) === 0,
    );
    await legacyCustomer.close();
    const ownerPage = await open(owners[0], "/admin/");
    check(
      "merchant_campus_queue_shows_items_and_legacy_queue_omits_them",
      (await ownerPage
        .locator("#commerceManagement")
        .innerText()
        .then((t) => t.includes(products[0].name + " × 3"))) &&
        (await ownerPage.locator("#orders article").count()) === 0,
    );
    await request(
      owners[0],
      "/admin/home-campus/" + east,
      "PUT",
      undefined,
      true,
    );
    await ownerPage.reload();
    await ownerPage.locator("#workspace").waitFor({ state: "visible" });
    check(
      "merchant_home_campus_survives_reload",
      (await ownerPage
        .locator("label")
        .filter({ hasText: "店铺所在校区" })
        .locator("select")
        .inputValue()) === String(east),
    );
    await request(owners[0], "/admin/home-campus/1", "PUT", undefined, true);

    for (const [action, title] of [
      ["ACCEPT", "确认接单"],
      ["START", "开始制作"],
      ["READY", "备餐完成"],
    ])
      await clickMutation(
        ownerPage,
        ownerPage
          .locator("#commerceManagement")
          .getByRole("button", { name: title, exact: true }),
        "/admin/commerce-orders/" + order.id + "/transition",
      );
    await request(customer, "/aftersales", "POST", {
      orderId: order.id,
      items: [{ orderItemId: order.items[0].id, quantity: 1 }],
      reason: "质量反馈",
      requestKey: "browser-refund-" + suffix,
    });
    await ownerPage
      .locator("#commerceManagement")
      .getByRole("button", { name: "刷新校园业务" })
      .click();
    const approval = ownerPage.locator("#commerceManagement form").filter({
      has: ownerPage.getByRole("button", { name: "同意退款", exact: true }),
    });
    await approval.locator("input").fill("审核同意");
    await clickMutation(
      ownerPage,
      approval.getByRole("button", { name: "同意退款", exact: true }),
      "/decision",
    );
    check(
      "quantity_refund_is_approved",
      (await request(customer, "/aftersales"))[0].status === "APPROVED",
    );
    await page.locator("#checkoutList input[name=reason]").first().focus();
    const rider = await browser.newContext();
    await request(
      rider,
      "/admin/login",
      "POST",
      { username: "campus_rider0_" + suffix, password },
      true,
    );
    const riderPage = await open(rider, "/rider/");
    check(
      "rider_can_match_pickup_to_shop_order",
      await riderPage
        .locator("#tasks")
        .innerText()
        .then((t) => t.includes("店内订单 #" + order.id) &&
          t.includes("食堂东门取餐窗 0") && !t.includes("东校门交接点")),
    );
    await clickMutation(
      riderPage,
      riderPage.getByRole("button", { name: "抢单", exact: true }),
      "/claim",
    );
    await riderPage.getByText("东校门交接点", { exact: false }).waitFor();
    check("only_assigned_rider_sees_campus_handoff_details",
      (await riderPage.locator("#tasks").innerText()).includes("东校门交接点"));
    await clickMutation(
      riderPage,
      riderPage.getByRole("button", { name: "已取餐", exact: true }),
      "/transition",
    );
    const code = await request(
      customer,
      "/checkouts/orders/" + order.id + "/code",
    );
    await riderPage.getByRole("textbox", { name: "顾客提供的送达码", exact: true }).fill(code);
    await clickMutation(
      riderPage,
      riderPage.getByRole("button", { name: "核验送达", exact: true }),
      "/transition",
    );
    check(
      "rider_delivery_completes_with_customer_code",
      (await request(customer, "/checkouts/" + checkout.id)).orders.find(
        (o) => o.id === order.id,
      ).fulfillment_status === "COMPLETED",
    );
    await request(
      customer,
      "/checkouts/orders/" + order2.id + "/cancel",
      "POST",
    );
    check(
      "another_shop_can_cancel_independently",
      (await request(customer, "/checkouts/" + checkout.id)).orders.find(
        (o) => o.id === order2.id,
      ).status === 5,
    );
    const favorite = products[0];
    await request(
      customer,
      "/favorites/" + favorite.type + "/" + favorite.id,
      "POST",
    );
    await request(customer, "/cart/reorder/" + order.id, "POST");
    await request(customer, "/feedback", "POST", {
      orderId: order.id,
      message: "配送体验反馈",
    });
    const replay = await request(customer, "/me/events/replay");
    check(
      "favorites_reorder_feedback_and_event_replay",
      replay.length > 0 && (await request(customer, "/favorites")).length === 1,
    );
    const now = Date.now(),
      start = new Date(now + 20 * 60000 + 8 * 3600000)
        .toISOString()
        .slice(0, 19),
      end = new Date(now + 40 * 60000 + 8 * 3600000).toISOString().slice(0, 19),
      cutoff = new Date(now + 10 * 60000 + 8 * 3600000)
        .toISOString()
        .slice(0, 19);
    const slot = await request(
      owners[0],
      "/admin/reservations",
      "POST",
      {
        startsAt: start,
        endsAt: end,
        cutoffAt: cutoff,
        prepareMinutes: 30,
        capacity: 1,
      },
      true,
    );
    const pickup = await request(customer, "/checkouts/quotes", "POST", {
      merchantIds: [stores[0].id],
      campusId: 1,
      fulfillmentMode: "PICKUP",
      reservationSlots: { [stores[0].id]: slot },
    });
    assert.equal(pickup.shops[0].delivery, 0);
    const p = await request(customer, "/checkouts/confirm", "POST", {
      quoteId: pickup.id,
      expectedVersion: 1,
    });
    await page.reload();
    await page.locator("#workspace").waitFor({ state: "visible" });
    const pendingPickup = page.locator('[data-checkout-id="' + p.id + '"]');
    await pendingPickup.getByText("取餐点：食堂东门取餐窗 0").waitFor();
    await clickMutation(page, pendingPickup.getByRole("button", { name: "统一模拟支付" }),
      "/checkouts/" + p.id + "/pay");
    check("unpaid_pickup_remains_payable_after_returning_to_page",
      (await request(customer, "/checkouts/" + p.id)).status === "PAID");
    const po = p.orders[0];
    for (const action of ["ACCEPT", "START", "READY"])
      await request(
        owners[0],
        "/admin/commerce-orders/" + po.id + "/transition",
        "POST",
        { action },
        true,
      );
    await page.locator("#refresh").click();
    await pendingPickup.getByText("请到店出示取餐码", { exact: true }).waitFor();
    const pickupCode = await clickMutation(
      page, pendingPickup.getByRole("button", { name: "查看本人取餐码" }),
      "/checkouts/orders/" + po.id + "/code", "GET");
    await pendingPickup.getByText("取餐码：" + pickupCode, { exact: false }).waitFor();
    check("customer_sees_code_next_to_pickup_order", true);
    await request(
      owners[0],
      "/admin/commerce-orders/" + po.id + "/transition",
      "POST",
      { action: "PICKUP", code: pickupCode },
      true,
    );
    check(
      "reservation_and_pickup_quote_has_no_delivery_fee",
      pickup.shops[0].slotId === slot,
    );
    check(
      "reserved_pickup_completes_with_one_time_code",
      (await request(customer, "/checkouts/" + p.id)).orders[0]
        .fulfillment_status === "COMPLETED",
    );
    const platformPage = await open(platform, "/platform/");
    await platformPage
      .locator("#commerceManagement")
      .getByRole("heading", { name: "平台骑手", exact: true })
      .waitFor();
    check(
      "all_four_role_pages_render_without_script_errors",
      errors.length === 0,
    );
    await page.locator("#refresh").focus();
    await page.locator("#checkoutScope").selectOption("all");
    const completed = page
      .locator("#checkoutList h3")
      .filter({ hasText: stores[0].name + " · 已完成" });
    await completed.nth(1).waitFor();
    check(
      "customer_receives_committed_delivery_event",
      (await completed.count()) === 2,
    );
    for (let i = 0; i < 21; i++) {
      await request(customer, "/cart", "PUT", {
        productType: products[0].type,
        productId: products[0].id,
        quantity: 1,
        remark: "",
      });
      const q = await request(customer, "/checkouts/quotes", "POST", {
        merchantIds: [stores[0].id],
        campusId: east,
        addressId: address,
        fulfillmentMode: "DELIVERY",
      });
      const c = await request(customer, "/checkouts/confirm", "POST", {
        quoteId: q.id,
        expectedVersion: q.version,
      });
      await request(customer, "/checkouts/" + c.id + "/close", "POST");
    }
    await page.reload();
    await page.locator("#workspace").waitFor({ state: "visible" });
    await page.locator("#checkoutScope").selectOption("history");
    check(
      "older_checkouts_are_reachable_and_stay_loaded_after_refresh",
      (await page
        .locator('[data-checkout-id="' + checkout.id + '"]')
        .count()) === 0,
    );
    await page
      .getByRole("button", { name: "加载更多结算", exact: true })
      .click();
    await page.locator('[data-checkout-id="' + checkout.id + '"]').waitFor();
    await Promise.all([
      page.waitForResponse((r) =>
        r.url().includes("/checkouts?scope=history&page=2&pageSize=20"),
      ),
      page.locator("#refresh").click(),
    ]);
    const history = page.locator('details[data-checkout-id="' + checkout.id + '"]');
    await history.waitFor();
    check("history_summaries_are_collapsed_by_default",
      !(await history.evaluate((detail) => detail.open)) &&
      (await history.locator("form").count()) === 0);
    await page.locator("#historyFilter input[name=shop]").fill(stores[0].name);
    await page.locator("#historyFilter select[name=status]").selectOption("COMPLETED");
    await page.getByRole("button", { name: "筛选历史订单" }).click();
    await history.locator("summary").click();
    await history.getByRole("button", { name: "再来一单" }).first().waitFor();
    check("history_filters_and_expands_a_selected_order", true);
    await verifyRecommendationRequotes(browser, { suffix, password, east, stores, owners, products, platformCampaign });
    if (options["controlled-model"] === "true") {
      await request(owners[0], "/admin/dishes", "POST", {
        name: "奶茶" + suffix,
        category: "验收",
        price: 22,
        stock: 20,
        status: 1,
        allergens: "",
        allergenReviewed: true,
      }, true);
      await page.locator("#campus").selectOption(String(east));
      await page.locator("#address").selectOption(String(address));
      const before = await request(customer, "/cart");
      const budgetOptions = await request(customer, "/campus-assistant/recommend", "POST", {
        campusId: east, addressId: address, fulfillmentMode: "DELIVERY",
        keywords: ["米饭" + suffix, "奶茶" + suffix], budget: 50,
      });
      check("recommended_budget_accepts_older_request_without_preferences", budgetOptions.candidates.length > 0);
      await page.locator("#campusPrompt").fill("验收推荐" + suffix);
      await clickMutation(
        page, page.locator("#campusChat").getByRole("button", { name: "发送" }),
        "/campus-assistant/chat",
      );
      const chat = page.locator("#campusMessages");
      const sameShop = chat.getByRole("heading", { name: /预计实付.*同一家店/ }).first();
      await sameShop.waitFor();
      check("controlled_model_renders_java_same_shop_candidates_without_writes",
        JSON.stringify(await request(customer, "/cart")) === JSON.stringify(before));
      await page.locator("#campusPrompt").fill("加购奶茶" + suffix);
      await clickMutation(page, page.locator("#campusChat").getByRole("button", { name: "发送" }),
        "/campus-assistant/chat");
      await chat.getByText("这道餐品有多个店铺或规格", { exact: false }).waitFor();
      check("controlled_model_same_name_asks_before_cart_mutation",
        JSON.stringify(await request(customer, "/cart")) === JSON.stringify(before));
      await clickMutation(page, sameShop.locator("..").getByRole("button", { name: "将本组合加入购物车" }),
        "/campus-assistant/apply-recommendation");
      check("controlled_model_candidate_applies_only_on_explicit_click",
        (await request(customer, "/cart")).length === before.length + 2);
    }
    assert.deepEqual(errors, [], "Browser pages must finish without script errors");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: path.join(path.dirname(output), "campus-customer-mobile.png"),
      fullPage: true,
    });
    await platformPage.screenshot({
      path: path.join(path.dirname(output), "campus-platform.png"),
      fullPage: true,
    });
    await riderPage.screenshot({
      path: path.join(path.dirname(output), "campus-rider.png"),
      fullPage: true,
    });
    fs.writeFileSync(
      output,
      JSON.stringify({ checks, errors, passed: true }, null, 2),
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
