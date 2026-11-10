const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.argv[2] || "playwright");
const root = path.resolve(__dirname, "../java-gateway/src/main/resources/static");
const checks = [];

async function fixture(browser, { userAgent, role = "CUSTOMER", signedIn = true, oauth = false, prepayUnknown = false, pagePath = "/campus/" } = {}) {
  const context = await browser.newContext(userAgent ? { userAgent } : {});
  const page = await context.newPage();
  const errors = [], requests = [];
  const state = { status: prepayUnknown ? "UNKNOWN" : "PENDING", actionReady: !prepayUnknown,
    action: null, paymentCreates: 0, scene: null, phone: null, recovery: null, upload: null, read: false, resolution: null };
  page.on("pageerror", (error) => errors.push(error.message));
  await page.addInitScript(() => {
    window.WeixinJSBridge = {
      invoke(name, parameters, callback) {
        window.bridgeCall = { name, parameters };
        callback({ err_msg: "get_brand_wcpay_request:ok" });
      },
    };
  });
  await page.route("**/*", async (route) => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname;
    requests.push(url.href);
    const json = (data, status = 200, errorCode) => route.fulfill({ status, contentType: "application/json",
      body: JSON.stringify({ code: status === 200 ? 1 : 0, data, errorCode, msg: errorCode || "ok" }) });
    if (url.hostname === "wx.tenpay.com") return route.fulfill({ contentType: "text/html", body: "WeChat cashier" });
    if (p.startsWith("/assets/")) return route.fulfill({
      contentType: p.endsWith(".js") ? "text/javascript" : "text/css", body: fs.readFileSync(path.join(root, p)),
    });
    if (["/campus/", "/campus/index.html", "/chat/index.html"].includes(p)) return route.fulfill({ contentType: "text/html", body: `<!doctype html><html lang="zh-CN"><head>
      <link rel="stylesheet" href="/assets/integrations.css"><script src="/assets/vendor/qrcodegen.js"></script>
      <script type="module" src="/fixture.js"></script></head><body><p id="notice"></p>
      <div id="auth"></div><div id="account"></div><main id="workspace"></main><button id="pay">支付</button>
      <div id="product"></div><div id="upload"></div><footer class="page-footer"><span></span></footer></body></html>` });
    if (p === "/fixture.js") return route.fulfill({ contentType: "text/javascript", body: `
      import {state,run} from '/assets/api.js';
      import {startPayment,resumePayment} from '/assets/payment-session.js';
      import {installIntegrationPanels,mediaControl} from '/assets/integrations.js';
      state.management=${role !== "CUSTOMER"}; state.me=${signedIn ? JSON.stringify({ userId: 1, adminId: 1, role, username: "fixture", merchantId: 1 }) : "null"};
      await installIntegrationPanels();
      document.querySelector('#upload').append(mediaControl('DISH',7));
      const refresh=async()=>{window.refreshed=(window.refreshed||0)+1};
      document.querySelector('#pay').onclick=()=>run(()=>startPayment('CHECKOUT','checkout-fixture','/checkouts/checkout-fixture/pay',refresh));
      await resumePayment(refresh);
      window.fixtureReady=true;
    ` });
    if (p === "/integrations/capabilities") return json({ paymentMode: "WECHAT_PARTNER", sms: true, maps: true, media: true, storage: "local" });
    if (p === "/auth/csrf") return json({ token: "fixture-csrf" });
    if (p === "/notifications/events") return route.fulfill({ contentType: "text/event-stream", body: ": heartbeat\n\n" });
    if (p === "/notifications") return json([{ id: 8, message: "备餐已完成", created_at: "2026-11-06T10:00:00", read_at: null }]);
    if (p === "/notifications/8/read") { state.read = true; return json(null); }
    if (p === "/admin/platform-coupons") return json([]);
    if (p === "/platform/merchant-payments") return json([{ merchant_id: 1, name: "测试店", sub_mch_id: "1234567", enabled: true }]);
    if (p === "/platform/payments") return json([{ id: "pay1", amount: 123, status: "UNKNOWN" }]);
    if (p === "/platform/refunds") return json([{ id: "refund1", amount: 23, status: "PROCESSING" }]);
    if (p === "/platform/reconciliation") return json({
      batches: [{ id: 1, bill_date: "2026-11-05", status: "DIFFERENCES", difference_count: 1 }],
      differences: [{ id: 2, kind: "PAYMENT_STATE", detail: "请重新查单", resolution: state.resolution,
        resolved_at: state.resolution ? "2026-11-06T10:00:00" : null }],
    });
    if (p === "/platform/reconciliation/differences/2/resolve") {
      state.resolution = request.postDataJSON().reason;
      return json(null);
    }
    if (p === "/payments" && request.method() === "POST") {
      state.paymentCreates++;
      const body = request.postDataJSON();
      state.scene = body.scene;
      assert.equal(body.sourceType, "CHECKOUT"); assert.equal(body.sourceId, "checkout-fixture");
      if (oauth) return json(null, 409, "WECHAT_OPENID_REQUIRED");
      const actions = {
        NATIVE: { type: "QR", payload: "weixin://wxpay/bizpayurl?pr=fixture" },
        H5: { type: "REDIRECT", payload: "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fixture" },
        JSAPI: { type: "JSAPI", payload: { appId: "wx-fixture", timeStamp: "1700000000", nonceStr: "nonce", package: "prepay_id=fixture", signType: "RSA", paySign: "fixture-signature" } },
      };
      state.action = actions[body.scene];
      return json({ id: "payment-fixture", mode: "WECHAT_PARTNER", status: state.status,
        action: state.actionReady ? state.action : { type: "NONE", payload: "" } });
    }
    if (p === "/payments/payment-fixture") return json({ id: "payment-fixture", status: state.status, mode: "WECHAT_PARTNER",
      action: state.actionReady && !["PAID", "CLOSED", "REFUNDED"].includes(state.status) ? state.action : { type: "NONE", payload: "" } });
    if (p === "/integrations/wechat/oauth/start") {
      assert.equal(request.method(), "POST");
      assert.equal(new URL(request.url()).searchParams.get("returnTo"), "/campus/");
      return json({ authorizationUrl: "https://integration.test/oauth-authorized" });
    }
    if (p === "/oauth-authorized") return route.fulfill({ contentType: "text/html", body: "OAuth redirect" });
    if (p === "/auth/phone/challenges") return json({ challengeId: "challenge-fixture" });
    if (p === "/auth/phone") { state.phone = request.postDataJSON(); return json(null); }
    if (p === "/auth/password/recovery") { state.recovery = request.postDataJSON(); return json(null); }
    if (p === "/media" && request.method() === "POST") {
      state.upload = { body: request.postDataBuffer().toString("latin1"), contentType: request.headers()["content-type"] };
      return json({ id: "image-fixture", url: "/media/image-fixture" });
    }
    if (p === "/media") return json([]);
    if (p === "/maps/geocode") { assert.equal(request.method(), "POST"); return json([{ formattedAddress: "测试校区一号楼", location: "116.40,39.90" }]); }
    if (p === "/maps/route") { assert.equal(request.method(), "POST"); return json({ distance: 800, duration: 300, navigationUrl: "https://uri.amap.com/navigation?to=116.40,39.90" }); }
    throw new Error("Unexpected integration fixture request: " + request.method() + " " + p);
  });
  await page.goto("https://integration.test" + pagePath);
  await page.waitForFunction(() => window.fixtureReady);
  return { context, page, state, errors, requests };
}

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.argv[3] ? { executablePath: process.argv[3] } : {}) });
  try {
    {
      const f = await fixture(browser, { signedIn: false });
      const recovery = f.page.locator("details").filter({ has: f.page.locator("summary", { hasText: /^通过已验证手机号找回密码$/ }) });
      await recovery.locator("summary").click();
      await recovery.getByLabel("账号", { exact: true }).fill("fixture");
      await recovery.getByLabel("手机号", { exact: true }).fill("13800000000");
      await recovery.getByRole("button", { name: "发送验证码" }).click();
      await f.page.waitForFunction(() => document.querySelector('#auth form').dataset.challenge);
      await recovery.getByLabel("短信验证码").fill("123456");
      await recovery.getByLabel("新密码（至少12位）").fill("new-password-fixture");
      await recovery.getByRole("button", { name: "重置密码" }).click();
      await f.page.waitForFunction(() => document.querySelector('#notice').textContent.includes('旧会话已失效'));
      assert.equal(f.state.recovery.challengeId, "challenge-fixture");
      assert.equal(f.state.recovery.newPassword, "new-password-fixture");
      assert.equal("phone" in f.state.recovery, false);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("signed-out account recovery uses a verified challenge without extra identity fields");
    }
    {
      const f = await fixture(browser, { role: "PLATFORM_ADMIN" });
      const platform = f.page.locator("details").filter({ has: f.page.locator("summary", { hasText: /^支付接入与运营处理$/ }) });
      await platform.locator("summary").click();
      await platform.getByRole("button", { name: "查看支付及重新查单" }).click();
      await platform.getByText(/金额 ¥1.23/).waitFor();
      await platform.getByRole("button", { name: "查看对账差异" }).click();
      await platform.getByLabel("核查与处理说明").fill("已核对渠道，等待重新查单");
      await platform.getByRole("button", { name: "记录差异处理意见" }).click();
      await platform.getByText(/处理意见：已核对渠道/).waitFor();
      assert.equal(f.state.resolution, "已核对渠道，等待重新查单");
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("platform amounts convert fen to yuan and reconciliation differences can be reviewed");
    }
    {
      const f = await fixture(browser);
      await f.page.locator("#pay").click();
      await f.page.getByRole("dialog").waitFor();
      await f.page.locator('dialog[aria-busy="false"]').waitFor();
      assert.equal(f.state.scene, "NATIVE");
      assert.ok(await f.page.locator("dialog canvas").evaluate((canvas) => canvas.width > 100));
      assert.ok(f.requests.every((value) => new URL(value).hostname === "integration.test"));
      f.state.status = "PAID";
      await f.page.getByRole("button", { name: "查询支付结果" }).click();
      await f.page.waitForFunction(() => window.refreshed === 1);
      assert.match(await f.page.locator("dialog").innerText(), /支付成功/);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("Native QR is generated locally and success requires server confirmation");
    }
    {
      const f = await fixture(browser, { userAgent: "Mozilla/5.0 (Linux; Android 15) Mobile" });
      await f.page.locator("#pay").click();
      await f.page.getByRole("button", { name: "前往微信收银台" }).click();
      await f.page.waitForURL("https://wx.tenpay.com/**");
      assert.equal(f.state.scene, "H5");
      const returnTo = new URL(f.page.url()).searchParams.get("redirect_url");
      assert.equal(new URL(returnTo).origin, "https://integration.test");
      assert.equal(new URL(returnTo).searchParams.get("payment_session"), "payment-fixture");
      const queries = f.requests.filter((value) => new URL(value).pathname === "/payments/payment-fixture").length;
      await f.page.goto(returnTo);
      await f.page.waitForFunction(() => window.fixtureReady);
      assert.ok(f.requests.filter((value) => new URL(value).pathname === "/payments/payment-fixture").length > queries);
      assert.equal(f.state.paymentCreates, 1);
      assert.match(await f.page.getByRole("dialog").innerText(), /等待支付/);
      assert.equal(await f.page.evaluate(() => window.refreshed || 0), 0);
      f.state.status = "PAID";
      await f.page.getByRole("button", { name: "查询支付结果" }).click();
      await f.page.waitForFunction(() => window.refreshed === 1);
      assert.equal(new URL(f.page.url()).searchParams.has("payment_session"), false);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("H5 uses a same-origin return, re-queries its existing session and never treats returning as payment success");
    }
    {
      const f = await fixture(browser, { userAgent: "Mozilla/5.0 iPhone MicroMessenger" });
      await f.page.locator("#pay").click();
      await f.page.locator('dialog[aria-busy="false"]').waitFor();
      f.state.status = "PAID";
      await f.page.getByRole("button", { name: "打开微信收银台" }).click();
      await f.page.waitForFunction(() => window.bridgeCall && window.refreshed === 1);
      assert.equal(f.state.scene, "JSAPI");
      assert.equal(await f.page.evaluate(() => window.bridgeCall.name), "getBrandWCPayRequest");
      assert.equal(await f.page.evaluate(() => window.bridgeCall.parameters.paySign), "fixture-signature");
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("WeChat JSAPI forwards signed parameters and queries the server after bridge success");
    }
    {
      const f = await fixture(browser, { prepayUnknown: true });
      await f.page.locator("#pay").click();
      await f.page.getByRole("dialog").waitFor();
      await f.page.locator('dialog[aria-busy="false"]').waitFor();
      assert.equal(await f.page.locator("dialog canvas").count(), 0);
      f.state.status = "PENDING";
      f.state.actionReady = true;
      await f.page.getByRole("button", { name: "查询支付结果" }).click();
      await f.page.locator("dialog canvas").waitFor();
      assert.equal(f.state.paymentCreates, 1);
      assert.match(await f.page.getByRole("dialog").innerText(), /等待支付/);
      assert.equal(await f.page.evaluate(() => window.refreshed || 0), 0);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("UNKNOWN prepay can recover its cashier action by querying without creating another payment");
    }
    {
      const f = await fixture(browser, { userAgent: "Mozilla/5.0 iPhone MicroMessenger", oauth: true, pagePath: "/campus/index.html" });
      await f.page.locator("#pay").click();
      await f.page.waitForURL("**/oauth-authorized");
      await f.context.close();
      checks.push("missing OpenID on an index.html alias starts OAuth with the canonical local return path");
    }
    {
      const f = await fixture(browser);
      await f.page.locator("#pay").click();
      await f.page.locator('dialog[aria-busy="false"]').waitFor();
      f.state.status = "REFUNDED";
      await f.page.getByRole("button", { name: "查询支付结果" }).click();
      await f.page.waitForFunction(() => window.refreshed === 1);
      assert.match(await f.page.getByRole("dialog").innerText(), /已退款/);
      assert.match(await f.page.locator("#notice").innerText(), /退款结果已确认/);
      assert.equal(await f.page.evaluate(() => sessionStorage.getItem("payment-session:1")), null);
      assert.equal(await f.page.locator("dialog canvas").count(), 0);
      assert.equal(f.state.paymentCreates, 1);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("REFUNDED clears payment recovery and refreshes without claiming payment success");
    }
    {
      const f = await fixture(browser, { role: "OWNER" });
      const phone = f.page.locator("details").filter({ has: f.page.locator("summary", { hasText: /^绑定账号手机号$/ }) });
      await phone.locator("summary").click();
      await phone.getByLabel("手机号", { exact: true }).fill("13800000000");
      await phone.getByRole("button", { name: "发送验证码" }).click();
      await f.page.waitForFunction(() => document.querySelector('#account form').dataset.challenge);
      await phone.getByLabel("短信验证码").fill("123456");
      await phone.getByLabel("当前密码").fill("current-password-fixture");
      await phone.getByRole("button", { name: "验证并绑定手机号" }).click();
      await f.page.waitForFunction(() => document.querySelector('#notice').textContent.includes('旧会话已失效'));
      assert.equal(f.state.phone.challengeId, "challenge-fixture");
      assert.equal(f.state.phone.currentPassword, "current-password-fixture");
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("management phone binding submits the verified challenge and requires re-login");
    }
    {
      const f = await fixture(browser);
      await f.page.locator("#upload input[type=file]").setInputFiles({ name: "image.png", mimeType: "image/png",
        buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmOYAAAAASUVORK5CYII=", "base64") });
      await f.page.locator("#upload").getByRole("button", { name: "上传图片" }).click();
      await f.page.waitForFunction(() => document.querySelector('#upload input[type=file]').value === '');
      assert.match(f.state.upload.contentType, /^multipart\/form-data; boundary=/);
      assert.match(f.state.upload.body, /name="purpose"\r\n\r\nDISH/);
      const maps = f.page.locator("details").filter({ has: f.page.locator("summary", { hasText: /^地址与路线参考$/ }) });
      await maps.locator("summary").click();
      await maps.getByLabel("校区、楼宇或完整地址").fill("测试校区");
      await maps.getByRole("button", { name: "查询候选地址" }).click();
      await maps.getByRole("button", { name: "选用此位置" }).click();
      assert.equal(await maps.locator('[name=destination]').inputValue(), "116.40,39.90");
      await maps.locator('[name=origin]').fill("116.39,39.91");
      await maps.getByRole("button", { name: "查询骑行路线" }).click();
      await maps.getByRole("link", { name: "打开高德导航" }).waitFor();
      const notifications = f.page.locator("details").filter({ has: f.page.locator("summary", { hasText: /^站内通知$/ }) });
      await notifications.locator("summary").click();
      await notifications.getByRole("button", { name: "标记已读" }).click();
      assert.equal(f.state.read, true);
      assert.deepEqual(f.errors, []);
      await f.context.close();
      checks.push("contextual multipart upload, selected map candidate, route and notification read work");
    }
    console.log(JSON.stringify({ passed: true, checks }));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
