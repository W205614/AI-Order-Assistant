// Real modules, intercepted HTTP only: no database, model, or mutable deployment.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.argv[2] || "playwright");
const root = path.resolve(
  __dirname,
  "../java-gateway/src/main/resources/static",
);
const checks = [];

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.argv[3] ? { executablePath: process.argv[3] } : {}),
  });
  try {
    for (const role of ["merchant", "platform", "rider"]) {
      const page = await browser.newPage();
      const errors = [];
      const reads = [];
      const endpoint =
        role === "merchant"
          ? "/admin/commerce-orders"
          : role === "platform"
            ? "/platform/delivery-tasks"
            : "/delivery-tasks";
      let releaseEvent, releaseReplay, replayEntered;
      let deferReplay = false;
      const eventReady = new Promise((resolve) => {
        releaseEvent = resolve;
      });
      const replayReady = new Promise((resolve) => {
        releaseReplay = resolve;
      });
      const enteredReplay = new Promise((resolve) => {
        replayEntered = resolve;
      });
      page.on("pageerror", (error) => errors.push(error.message));
      await page.route("**/*", async (route) => {
        const url = new URL(route.request().url()),
          p = url.pathname;
        const json = (data) =>
          route.fulfill({
            contentType: "application/json",
            body: JSON.stringify({ code: 1, msg: "ok", data }),
          });
        if (p === "/rider/" || p.startsWith("/assets/"))
          return route.fulfill({
            body: fs.readFileSync(
              path.join(root, p === "/rider/" ? "rider/index.html" : p),
            ),
            contentType: p.endsWith(".js")
              ? "text/javascript"
              : p.endsWith(".css")
                ? "text/css"
                : p.endsWith(".svg")
                  ? "image/svg+xml"
                  : "text/html",
          });
        if (p === "/test/")
          return route.fulfill({
            contentType: "text/html",
            body: '<div id="notice"></div><div id="merchants"></div><div id="workspace"><nav class="task-nav"></nav></div><script type="module" src="/fixture.js"></script>',
          });
        if (p === "/fixture.js")
          return route.fulfill({
            contentType: "text/javascript",
            body: `import {state} from '/assets/api.js'; import {mountCommerce} from '/assets/commerce-admin.js'; state.me={role:'${role === "platform" ? "PLATFORM_ADMIN" : "STAFF"}'}; await mountCommerce();`,
          });
        if (p === "/auth/me")
          return json({ adminId: 7, username: "fixture", role: "RIDER" });
        if (p === "/auth/csrf") return json({ token: "fixture" });
        if (p === endpoint) {
          const scope = url.searchParams.get("scope"),
            number = Number(url.searchParams.get("page"));
          assert.equal(url.searchParams.get("pageSize"), "50");
          assert.ok(["active", "history"].includes(scope));
          assert.ok(number >= 1 && number <= 1000000);
          reads.push({ scope, page: number });
          if (deferReplay) {
            deferReplay = false;
            replayEntered();
            await replayReady;
          }
          const count = scope === "active" && number === 1 ? 50 : 1;
          return json(
            Array.from({ length: count }, (_, index) => ({
              id: index + number * 100,
              order_id: index + 1,
              user_seq: index + 1,
              merchant_id: 1,
              merchant_name: "测试店",
              campus_id: 1,
              origin_campus_name: "测试校区",
              version: 1,
              fulfillment_status:
                scope === "history" ? "COMPLETED" : "PENDING_ACCEPTANCE",
              status: scope === "history" ? "DELIVERED" : "ASSIGNED",
              fulfillment_mode: "DELIVERY",
              ready_for_preparation: true,
              total_amount: 10,
              refunded_amount: 0,
              items: [],
              recipient: null,
            })),
          );
        }
        if (p === "/rider/events") {
          await eventReady;
          return route.fulfill({
            contentType: "text/event-stream",
            body: "id: 1\nevent: commerce\ndata: {}\n\n",
          });
        }
        if (p.startsWith("/admin/") || p.startsWith("/platform/"))
          return json([]);
        throw Error("Unexpected fixture request: " + p);
      });
      await page.goto(
        "http://127.0.0.1:19876/" + (role === "rider" ? "rider/" : "test/"),
      );
      const filter = page.getByLabel(
        (role === "merchant"
          ? "商户订单"
          : role === "platform"
            ? "平台配送任务"
            : "骑手任务") + "列表范围",
      );
      await filter.waitFor();
      const controls = page.locator(".list-pagination").filter({ has: filter });
      assert.deepEqual(reads[0], { scope: "active", page: 1 });
      assert.equal(
        await controls
          .getByRole("button", { name: "上一页", exact: true })
          .isDisabled(),
        true,
      );
      await controls.getByRole("button", { name: "下一页", exact: true }).click();
      await controls.getByText("第 2 页 · 每页 50 条", { exact: true }).waitFor();
      assert.deepEqual(reads.at(-1), { scope: "active", page: 2 });
      assert.equal(
        await controls
          .getByRole("button", { name: "下一页", exact: true })
          .isDisabled(),
        true,
      );
      await filter.selectOption("history");
      await controls.getByText("第 1 页 · 每页 50 条", { exact: true }).waitFor();
      assert.deepEqual(reads.at(-1), { scope: "history", page: 1 });
      if (role !== "merchant")
        await page
          .getByText("历史任务不再展示收货信息", { exact: true })
          .waitFor();
      await filter.selectOption("active");
      await page.waitForFunction(() =>
        [...document.querySelectorAll("button")].some(
          (button) => button.textContent === "下一页" && !button.disabled,
        ),
      );
      assert.deepEqual(reads.at(-1), { scope: "active", page: 1 });
      checks.push(
        role + ": active default, bounded next/previous, history resets page",
      );
      if (role === "rider") {
        // A push may start while idle and finish after a rider types an exception.
        deferReplay = true;
        releaseEvent();
        await enteredReplay;
        const reason = page.locator("#tasks [name=reason]").first();
        await reason.fill("尚未提交的异常说明");
        releaseReplay();
        await page.waitForLoadState("networkidle");
        assert.equal(await reason.inputValue(), "尚未提交的异常说明");
        assert.equal(
          await page.evaluate(() => sessionStorage.getItem("rider-events:7")),
          null,
        );
        await page.locator("#refresh").focus();
        await page.waitForFunction(
          () => sessionStorage.getItem("rider-events:7") === "1",
        );
        checks.push(
          "rider: in-flight push preserves editing and pending cursor",
        );
      }
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log(JSON.stringify({ passed: true, checks }));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
