const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.argv[2] || "playwright");
const root = path.resolve(__dirname, "../java-gateway/src/main/resources/static");
const checks = [];

async function fixture(browser, management = false) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [], notificationReads = [], cancelled = [];
  const accounts = management ? {
    alpha: { adminId: 1, username: "alpha", role: "OWNER", merchantId: 7 },
    beta: { adminId: 2, username: "beta", role: "STAFF", merchantId: 8 },
  } : {
    alpha: { userId: 1, username: "alpha", role: "CUSTOMER" },
    beta: { userId: 2, username: "beta", role: "CUSTOMER" },
  };
  let actor, holdNotification = false, pendingNotification, holdLogout = false, pendingLogout;
  let logoutReady;
  const logoutRequested = new Promise((resolve) => { logoutReady = resolve; });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("requestfailed", (request) => cancelled.push(new URL(request.url()).pathname));
  await page.addInitScript(() => {
    const original = window.fetch;
    const probe = window.notificationProbe = { opened: 0, aborted: 0, active: 0, maximumActive: 0 };
    window.fetch = (input, options) => {
      if (new URL(input, location.href).pathname !== "/notifications/events") return original(input, options);
      const { signal } = options;
      if (signal.aborted) return Promise.reject(signal.reason);
      if (probe.nextStatus) return Promise.resolve(new Response(null, { status: probe.nextStatus }));
      probe.opened++;
      probe.active++;
      probe.maximumActive = Math.max(probe.active, probe.maximumActive);
      let live = true;
      return Promise.resolve(new Response(new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode(": heartbeat\n\n"));
        probe.finishLatest = () => {
          if (!live) return;
          live = false;
          probe.active--;
          controller.close();
        };
        signal.addEventListener("abort", () => {
          probe.aborted++;
          if (!live) return;
          live = false;
          probe.active--;
          controller.error(signal.reason);
        }, { once: true });
      } }), { headers: { "Content-Type": "text/event-stream" } }));
    };
  });
  await page.route("**/*", async (route) => {
    const request = route.request(), p = new URL(request.url()).pathname;
    const json = (data, status = 200) => route.fulfill({ status, contentType: "application/json",
      body: JSON.stringify({ code: status === 200 ? 1 : 0, data, msg: status === 401 ? "会话失效" : "ok" }) });
    if (p.startsWith("/assets/")) return route.fulfill({ contentType: "text/javascript", body: fs.readFileSync(path.join(root, p)) });
    if (p === "/campus/" || p === "/admin/") return route.fulfill({ contentType: "text/html", body: `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
      <script type="module" src="/fixture.js"></script></head><body><p id="notice"></p>
      <section id="auth"><form id="login"><input name="username" aria-label="账号" required>
      <input name="password" type="password" aria-label="密码" required><button>登录</button></form></section>
      <section id="account" hidden><p id="identity"></p><button id="logout">退出登录</button>
      <form id="password"><input name="oldPassword"><input name="newPassword"><button>修改密码</button></form></section>
      <main id="workspace" hidden></main></body></html>` });
    if (p === "/fixture.js") return route.fulfill({ contentType: "text/javascript", body: `
      import {initAuth} from '/assets/auth.js';
      await initAuth(async()=>{window.workspaceLoads=(window.workspaceLoads||0)+1});
      window.fixtureReady=true;
    ` });
    if (p === "/integrations/capabilities") return json({ paymentMode: "SIMULATED", sms: false, maps: false, media: true });
    if (p === "/auth/csrf") return json({ token: "fixture-csrf" });
    if (p === "/auth/me") return actor ? json(actor) : json(null, 401);
    if (p === "/auth/login" || p === "/admin/login") {
      actor = accounts[request.postDataJSON().username];
      assert.ok(actor);
      return json(actor);
    }
    if (p === "/expired") { actor = null; return json(null, 401); }
    if (p === "/auth/logout") {
      actor = null;
      if (holdLogout) { pendingLogout = () => json(null); logoutReady(); return; }
      return json(null);
    }
    if (p === "/admin/platform-coupons") return json([]);
    if (p === "/notifications") {
      const name = actor.username;
      notificationReads.push({ username: name, management: request.headers()["x-session-type"] === "management" });
      const data = [{ id: name === "alpha" ? 11 : 22, message: "PRIVATE_" + name.toUpperCase(),
        created_at: "2026-11-06T10:00:00", read_at: null }];
      if (holdNotification) { holdNotification = false; pendingNotification = () => json(data); return; }
      return json(data);
    }
    throw new Error("Unexpected account fixture request: " + p);
  });
  await page.goto("https://account.test" + (management ? "/admin/" : "/campus/"));
  await page.waitForFunction(() => window.fixtureReady);
  const login = async (username) => {
    await page.locator('#login [name="username"]').fill(username);
    await page.locator('#login [name="password"]').fill("fixture-password");
    await page.locator("#login button").click();
    await page.locator("#identity").filter({ hasText: username }).waitFor();
    await page.locator("#workspace:not([hidden])").waitFor();
  };
  const expire = async () => {
    await page.evaluate(async () => {
      const { api } = await import("/assets/api.js");
      try { await api("/expired"); } catch (error) { if (error.status !== 401) throw error; }
    });
  };
  return { context, page, login, expire, errors, notificationReads, cancelled,
    holdNotification: () => { holdNotification = true; },
    releaseNotification: async () => { if (pendingNotification) await pendingNotification(); },
    holdLogout: () => { holdLogout = true; },
    waitForLogout: () => logoutRequested,
    releaseLogout: async () => { await pendingLogout(); },
  };
}

async function assertClean(page) {
  assert.equal(await page.locator("#integrationServices").count(), 0);
  assert.equal(await page.getByText("PRIVATE_ALPHA", { exact: true }).count(), 0);
  assert.equal(await page.getByText("绑定账号手机号", { exact: true }).count(), 0);
  assert.equal(await page.locator("#auth").isVisible(), true);
}

async function workspaceFixture(browser, kind, paymentMode = "SIMULATED") {
  const context = await browser.newContext(), page = await context.newPage();
  const errors = [], cancelled = [];
  let actor = "alpha", holdChat = false, pendingChat, chatReady;
  const chatStarted = new Promise((resolve) => { chatReady = resolve; });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("requestfailed", (request) => cancelled.push(new URL(request.url()).pathname));
  await page.route("**/*", async (route) => {
    const request = route.request(), p = new URL(request.url()).pathname;
    const json = (data, status = 200) => route.fulfill({ status, contentType: "application/json",
      body: JSON.stringify({ code: status === 200 ? 1 : 0, data, msg: status === 401 ? "会话失效" : "ok" }) });
    if (p.startsWith("/assets/") || ["/chat/", "/campus/"].includes(p)) {
      const file = p === "/chat/" ? "chat/index.html" : p === "/campus/" ? "campus/index.html" : p;
      return route.fulfill({ contentType: p.endsWith(".js") ? "text/javascript"
        : p.endsWith(".css") ? "text/css" : p.endsWith(".svg") ? "image/svg+xml" : "text/html",
      body: fs.readFileSync(path.join(root, file)) });
    }
    if (p === "/integrations/capabilities") return json({ paymentMode, sms: false, maps: true, media: true });
    if (p === "/auth/csrf") return json({ token: "fixture-csrf" });
    if (p === "/auth/me" || p === "/auth/login") {
      if (p === "/auth/login") actor = request.postDataJSON().username;
      return actor ? json({ userId: actor === "alpha" ? 1 : 2, username: actor, role: "CUSTOMER" }) : json(null, 401);
    }
    if (p === "/expired") { actor = null; return json(null, 401); }
    if (p === "/notifications/events") return route.fulfill({ contentType: "text/event-stream", body: ": heartbeat\n\n" });
    if (["/order/events", "/me/events"].includes(p)) return route.fulfill({ status: 403, body: "" });
    if (p === "/merchants") return json([{ id: 1, name: "Fixture Shop" }]);
    if (p === "/merchants/1") return json({ name: "Fixture Shop", enabled: true, acceptingOrders: true,
      opensAt: "00:00", closesAt: "23:59", deliveryRegions: ["Campus"] });
    if (p === "/user/preferences") return json({});
    if (p === "/dish/list") return json({ items: [] });
    if (p === "/order/list") return json({ total: actor === "alpha" ? 1 : 0, items: actor === "alpha" ? [{
      id: 101, userSeq: 1, status: 4, items: [{ dishName: "PRIVATE_A_ORDER", quantity: 1 }], totalAmount: 10,
      paymentStatus: "SIMULATED_PAID", createTime: "2026-11-06T10:00:00", commerceVersion: 1,
    }] : [] });
    if (p === "/order/safety-context") return json({ allergens: [], needsClarification: false });
    if (p === "/campuses") return json([{ id: 1, name: "Fixture Campus" }]);
    if (p === "/campuses/1/merchants") return json([{ id: 1, name: "Fixture Shop", fee: 0 }]);
    if (p === "/campus-menu/1") return json([]);
    if (p === "/addresses") return json(actor === "alpha" ? [{ id: 101, campus_id: 1,
      recipient_name: "PRIVATE_A_SAVED_ADDRESS", detail: "PRIVATE_A_SAVED_ADDRESS_DETAIL" }] : []);
    if (p === "/cart") return json(actor === "alpha" ? [{ id: 1, product_id: 1, product_type: "SKU", merchant_id: 1,
      merchant_name: "PRIVATE_A_SAVED_CART", product_name: "PRIVATE_A_SAVED_CART_DISH", quantity: 1, version: 1 }] : []);
    if (p === "/campus-assistant/chat") {
      const data = { reply: actor === "alpha" ? "PRIVATE_A_CHAT_REPLY" : "PRIVATE_B_CHAT_REPLY", toolCalls: [] };
      if (holdChat) { holdChat = false; pendingChat = () => json(data); chatReady(); return; }
      return json(data);
    }
    if (p === "/campus-assistant/recommend") return json({ message: "PRIVATE_A_RECOMMENDATION", candidates: [] });
    if (p === "/payments" || p === "/payments/payment-alpha") return json({ id: "payment-alpha", mode: "WECHAT_PARTNER",
      status: "PENDING", action: { type: "QR", payload: "weixin://wxpay/bizpayurl?pr=private-alpha" } });
    if (p === "/maps/geocode") return json([{ formattedAddress: "PRIVATE_A_DESTINATION", location: "116.40,39.90" }]);
    if (["/notifications", "/media", "/order/drafts/pending", "/coupons", "/coupons/mine", "/checkouts", "/favorites", "/feedback", "/reservations/1"].includes(p)) return json([]);
    throw Error("Unexpected complete-page fixture request: " + p);
  });
  await page.goto("https://workspace.test" + (kind === "single" ? "/chat/" : "/campus/"));
  await page.locator("#workspace:not([hidden])").waitFor();
  return { context, page, errors, cancelled,
    holdChat: () => { holdChat = true; },
    chatStarted: () => chatStarted,
    releaseChat: () => pendingChat(),
    expire: () => page.evaluate(async () => {
      const { api } = await import("/assets/api.js");
      try { await api("/expired"); } catch (error) { if (error.status !== 401) throw error; }
    }),
    loginBeta: async () => {
      await page.locator('#login [name="username"]').fill("beta");
      await page.locator('#login [name="password"]').fill("fixture-password");
      await page.locator("#login button").click();
      await page.locator("#identity").filter({ hasText: "beta" }).waitFor();
      await page.locator("#workspace:not([hidden])").waitFor();
    },
  };
}

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.argv[3] ? { executablePath: process.argv[3] } : {}) });
  try {
    {
      const f = await fixture(browser);
      await f.login("alpha");
      await f.page.waitForFunction(() => window.notificationProbe.active === 1);
      await f.page.getByText("站内通知", { exact: true }).click();
      f.holdNotification();
      const started = f.page.waitForRequest((r) => new URL(r.url()).pathname === "/notifications");
      await f.page.getByRole("button", { name: "刷新通知" }).click();
      await started;
      await f.expire();
      await assertClean(f.page);
      await f.login("beta");
      await f.releaseNotification();
      await f.page.getByText("站内通知", { exact: true }).click();
      assert.equal(await f.page.getByText("PRIVATE_BETA", { exact: true }).isVisible(), true);
      assert.equal(await f.page.getByText("PRIVATE_ALPHA", { exact: true }).count(), 0);
      assert.equal(await f.page.getByText("绑定账号手机号", { exact: true }).count(), 1);
      assert.deepEqual(f.notificationReads.map((read) => read.username), ["alpha", "alpha", "beta"]);
      assert.ok(f.cancelled.includes("/notifications"));
      assert.deepEqual(await f.page.evaluate(() => ({ opened: notificationProbe.opened, aborted: notificationProbe.aborted,
        active: notificationProbe.active, maximumActive: notificationProbe.maximumActive })),
      { opened: 2, aborted: 1, active: 1, maximumActive: 1 });
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("customer 401 removes account A data, aborts its pending notification read/SSE and loads only account B notifications");
    }
    {
      const f = await fixture(browser, true);
      await f.login("alpha");
      assert.equal(await f.page.getByText("店铺图片", { exact: true }).count(), 1);
      await f.page.waitForFunction(() => window.notificationProbe.active === 1);
      await f.page.evaluate(() => notificationProbe.finishLatest());
      await f.expire();
      await assertClean(f.page);
      await f.login("beta");
      await f.page.getByText("站内通知", { exact: true }).click();
      assert.equal(await f.page.getByText("PRIVATE_BETA", { exact: true }).isVisible(), true);
      assert.equal(await f.page.getByText("PRIVATE_ALPHA", { exact: true }).count(), 0);
      assert.equal(await f.page.getByText("店铺图片", { exact: true }).count(), 0);
      assert.equal(await f.page.getByText("平台联合优惠活动", { exact: true }).count(), 0);
      await f.page.waitForTimeout(5300);
      assert.equal(await f.page.evaluate(() => notificationProbe.opened), 2);
      assert.equal(await f.page.evaluate(() => notificationProbe.aborted), 1);
      assert.ok(f.notificationReads.every((read) => read.management));
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("owner to staff login removes prior merchant panels and cancels the old SSE reconnect timer");
    }
    {
      const f = await fixture(browser);
      await f.login("alpha");
      await f.page.waitForFunction(() => window.notificationProbe.active === 1);
      f.holdLogout();
      await f.page.getByRole("button", { name: "退出登录" }).click();
      await f.waitForLogout();
      await assertClean(f.page);
      assert.equal(await f.page.evaluate(() => notificationProbe.aborted), 1);
      assert.equal(await f.page.evaluate(() => notificationProbe.active), 0);
      const reloaded = f.page.waitForEvent("domcontentloaded");
      await f.releaseLogout();
      await reloaded;
      await f.page.waitForFunction(() => window.fixtureReady);
      await assertClean(f.page);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("logout disposes private panels and aborts the notification stream before the logout network response or reload");
    }
    {
      const f = await fixture(browser);
      await f.login("alpha");
      await f.page.waitForFunction(() => window.notificationProbe.active === 1);
      await f.page.evaluate(() => {
        notificationProbe.nextStatus = 401;
        notificationProbe.finishLatest();
      });
      await f.page.locator("#auth:not([hidden])").waitFor();
      await assertClean(f.page);
      assert.equal(await f.page.evaluate(async () => (await import("/assets/api.js")).state.me), null);
      assert.equal(await f.page.evaluate(() => notificationProbe.aborted), 1);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("a notification SSE authentication failure uses the same disposal path and returns to login");
    }
    for (const kind of ["single", "campus"]) {
      const f = await workspaceFixture(browser, kind), { page } = f;
      if (kind === "campus") await page.locator(".address-manager > summary").click();
      const form = kind === "single" ? "#receipt" : "#addressForm";
      const phone = form + (kind === "single" ? " [name=recipientPhone]" : " [name=phone]");
      const address = form + (kind === "single" ? " [name=deliveryAddress]" : " [name=detail]");
      await page.locator(form + " [name=recipientName]").fill("PRIVATE_A_RECIPIENT");
      await page.locator(phone).fill("13800000000");
      await page.locator(address).fill("PRIVATE_A_ADDRESS");
      if (kind === "campus") {
        await page.locator("#campusChat [name=message]").fill("PRIVATE_A_CHAT_MESSAGE");
        await page.locator("#campusChat button").click();
        await page.getByText("PRIVATE_A_CHAT_REPLY", { exact: true }).waitFor();
        await page.locator("#recommendForm [name=budget]").fill("30");
        await page.locator("#recommendForm [name=keywords]").fill("rice");
        await page.locator("#recommendForm button").click();
        await page.getByText("PRIVATE_A_RECOMMENDATION", { exact: true }).waitFor();
        f.holdChat();
        await page.locator("#campusChat [name=message]").fill("PRIVATE_A_LATE_MESSAGE");
        await page.locator("#campusChat button").click();
        await f.chatStarted();
        await page.locator('#campusSafety input[value="花生"]').check();
      }
      await f.expire();
      assert.equal(await page.locator(form + " [name=recipientName]").inputValue(), "");
      assert.equal(await page.locator(phone).inputValue(), "");
      assert.equal(await page.locator(address).inputValue(), "");
      await f.loginBeta();
      if (kind === "campus") {
        await f.releaseChat();
        assert.equal(await page.locator("#campusMessages").innerText(), "");
        assert.equal(await page.locator("#recommendations").innerText(), "");
        assert.equal(await page.locator("#pendingSafetyEdits").isVisible(), false);
        assert.equal(await page.locator('#campusSafety input[value="花生"]').isChecked(), false);
        assert.ok(f.cancelled.includes("/campus-assistant/chat"));
        await page.locator("#campusChat [name=message]").fill("B current message");
        await page.locator("#campusChat button").click();
        await page.getByText("PRIVATE_B_CHAT_REPLY", { exact: true }).waitFor();
      }
      assert.equal(await page.locator(form + " [name=recipientName]").inputValue(), "");
      assert.equal(await page.locator(phone).inputValue(), "");
      assert.equal(await page.locator(address).inputValue(), "");
      assert.equal(await page.getByText("PRIVATE_A_ORDER × 1", { exact: true }).count(), 0);
      assert.equal(await page.getByText("PRIVATE_A_SAVED_CART", { exact: true }).count(), 0);
      assert.equal(await page.getByText("PRIVATE_A_SAVED_ADDRESS · PRIVATE_A_SAVED_ADDRESS_DETAIL", { exact: true }).count(), 0);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push(kind + " complete page clears private receipt/address fields before B sees the workspace"
        + (kind === "campus" ? "; old chat/recommendation/unsaved safety state and delayed replies cannot return" : ""));
    }
    for (const kind of ["payment", "navigation"]) {
      const f = await workspaceFixture(browser, "campus", "WECHAT_PARTNER");
      await f.page.evaluate(async (type) => {
        if (type === "payment")
          await (await import("/assets/payment-session.js")).startPayment("ORDER", "101", "/order/1/pay", async () => {});
        else await (await import("/assets/integrations.js")).navigateToAddress("PRIVATE_A_DESTINATION");
      }, kind);
      await f.page.getByRole("dialog").waitFor();
      await f.expire();
      assert.equal(await f.page.locator("dialog").count(), 0);
      await f.loginBeta();
      assert.equal(await f.page.locator("dialog").count(), 0);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push(kind + " body dialog closes on account invalidation and stays closed for B");
    }
    console.log(JSON.stringify({ passed: true, checks }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
