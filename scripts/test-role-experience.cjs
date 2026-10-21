// Focused role UX regressions against a disposable deployment, never daily data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const options = Object.fromEntries(process.argv.slice(2).reduce((pairs, arg, index, args) => {
  if (arg.startsWith('--')) pairs.push([arg.slice(2), args[index + 1]]);
  return pairs;
}, []));
const base = options['base-url'] || 'http://127.0.0.1:19090';
const target = new URL(base);
assert.ok(['localhost', '127.0.0.1'].includes(target.hostname) && ['19090', '19092'].includes(target.port), 'Disposable local test ports only');
const { chromium } = require(options['playwright-module'] || 'playwright');
const env = Object.fromEntries(fs.readFileSync(options['env-file'] || '.github/compose-ci.env', 'utf8')
  .replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const output = options.report || 'load/results/role-experience.json';
const checks = [], errors = [];
function check(name, passed) {
  checks.push({ name, passed: Boolean(passed) });
  console.log((passed ? 'Passed: ' : 'Failed: ') + name);
}
async function request(context, route, method = 'GET', data, management = true) {
  const headers = management ? { 'X-Session-Type': 'management' } : {};
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = (await (await context.request.get(base + '/auth/csrf')).json()).data.token;
  if (route === '/checkouts/confirm') headers['Idempotency-Key'] = require('node:crypto').randomUUID();
  const response = await context.request.fetch(base + route, { method, data, headers });
  const body = await response.json();
  assert.equal(response.status(), 200, route + ': ' + body.msg);
  assert.equal(body.code, 1, route + ': ' + body.msg);
  return body.data;
}
async function forbidden(context, route, method = 'GET', data, extraHeaders = {}) {
  const headers = { 'X-Session-Type': 'management', ...extraHeaders };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = (await (await context.request.get(base + '/auth/csrf')).json()).data.token;
  const response = await context.request.fetch(base + route, { method, data, headers });
  assert.equal(response.status(), 403, route + ' must reject the staff session');
  assert.equal((await response.json()).code, 0);
}
async function mutate(page, action, route, method = 'POST', status = 200) {
  const response = page.waitForResponse(r => r.url().includes(route) && r.request().method() === method);
  await action();
  const result = await response;
  assert.equal(result.status(), status, route + ': ' + await result.text());
  // Wait until the form's async reload finishes before inspecting replacement controls.
  await page.waitForFunction(() => document.querySelector('#notice').textContent !== '正在处理…');
}
async function open(context, route) {
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base + route);
  await page.locator('#workspace').waitFor({ state: 'visible' });
  return page;
}
const panel = (page, title) => page.locator('#commerceManagement article').filter({ has: page.getByRole('heading', { name: title, exact: true, includeHidden: true }) }).last();

(async () => {
  const browser = await chromium.launch({ headless: true, ...(options['browser-path'] ? { executablePath: options['browser-path'] } : {}) });
  try {
    const suffix = Date.now().toString(36), password = 'Role-experience-' + suffix;
    const platform = await browser.newContext();
    await request(platform, '/admin/login', 'POST', { username: env.PLATFORM_ADMIN_USERNAME, password: env.PLATFORM_ADMIN_PASSWORD });
    const campus = await request(platform, '/platform/campuses', 'POST', { name: '体验校区' + suffix });
    const emptyCampus = await request(platform, '/platform/campuses', 'POST', { name: '未配置校区' + suffix });
    const merchant = await request(platform, '/platform/merchants', 'POST', { name: '角色体验店' + suffix, ownerUsername: 'ux_owner_' + suffix, ownerPassword: password });
    const owner = await browser.newContext();
    await request(owner, '/admin/login', 'POST', { username: 'ux_owner_' + suffix, password });
    await request(owner, '/admin/delivery-rules', 'PUT', { campusId: campus, enabled: true, fee: 4.5, minimumAmount: 18, etaMin: 35, etaMax: 75, expectedVersion: 0 });
    await request(owner, '/admin/dishes', 'POST', { name: '体验饭', category: '体验', price: 20, stock: 10, status: 1, allergens: '', allergenReviewed: true });
    await request(owner, '/admin/staff', 'POST', { username: 'ux_staff_' + suffix, password });
    const staff = await browser.newContext();
    await request(staff, '/admin/login', 'POST', { username: 'ux_staff_' + suffix, password });
    const riderNames = ['ux_rider_' + suffix, 'ux_relay_' + suffix];
    for (const username of riderNames) await request(platform, '/platform/riders', 'POST', { username, password, campusIds: [campus] });
    const customer = await browser.newContext();
    await request(customer, '/auth/register', 'POST', { username: 'ux_customer_' + suffix, password, nickname: '体验同学' }, false);
    const address = await request(customer, '/addresses', 'POST', { campusId: campus, recipientName: '体验同学', phone: '13800000000', detail: '体验楼 101' }, false);
    const product = (await request(customer, '/campus-menu/' + merchant.id, 'GET', undefined, false))[0].product;
    await request(customer, '/cart', 'PUT', { productType: product.type, productId: product.id, quantity: 1, remark: '' }, false);
    const quote = await request(customer, '/checkouts/quotes', 'POST', { merchantIds: [merchant.id], campusId: campus, addressId: address, fulfillmentMode: 'DELIVERY' }, false);
    const checkout = await request(customer, '/checkouts/confirm', 'POST', { quoteId: quote.id, expectedVersion: quote.version }, false);
    await request(customer, '/checkouts/' + checkout.id + '/pay', 'POST', undefined, false);
    const order = checkout.orders[0];

    console.log('Disposable role fixtures ready');
    const ownerPage = await open(owner, '/admin/');
    let rules = panel(ownerPage, '所属校区与跨校区配送');
    await rules.locator('..').locator('summary').click();
    await rules.getByLabel('目的校区').selectOption(String(campus));
    check('delivery_rule_editor_loads_saved_values', await rules.locator('[name=fee]').inputValue() === '4.5'
      && await rules.locator('[name=minimumAmount]').inputValue() === '18'
      && await rules.locator('[name=etaMin]').inputValue() === '35'
      && await rules.locator('[name=etaMax]').inputValue() === '75');
    await rules.getByLabel('目的校区').selectOption(String(emptyCampus));
    check('unconfigured_campus_does_not_appear_enabled', !await rules.locator('[name=enabled]').isChecked());
    await rules.getByLabel('目的校区').selectOption(String(campus));
    await rules.locator('[name=fee]').fill('5.5');
    await mutate(ownerPage, () => rules.getByRole('button', { name: '保存配送规则' }).click(), '/admin/delivery-rules', 'PUT');
    const saved = (await request(owner, '/admin/delivery-rules')).find(r => r.campus_id === campus);
    check('editing_fee_preserves_minimum_and_eta', saved.minimum_amount === 18 && saved.eta_min === 35 && saved.eta_max === 75 && saved.fee === 5.5);
    rules = panel(ownerPage, '所属校区与跨校区配送');
    check('saved_panel_stays_open', await rules.locator('..').evaluate(el => el.open));
    // Restore fixture rule after the baseline probe so later role checks stay independent.
    await request(owner, '/admin/delivery-rules', 'PUT', { campusId: campus, enabled: true, fee: 4.5, minimumAmount: 18, etaMin: 35, etaMax: 75, expectedVersion: saved.version });

    const staffPage = await open(staff, '/admin/');
    check('staff_has_no_owner_configuration', await staffPage.locator('[data-owner]:visible').count() === 0
      && await staffPage.getByRole('heading', { name: '所属校区与跨校区配送', exact: true }).count() === 0);
    await forbidden(staff, '/admin/delivery-rules', 'PUT', {
      campusId: campus, enabled: false, fee: 0, minimumAmount: 0, etaMin: 0, etaMax: 0, expectedVersion: saved.version + 1
    });
    await forbidden(staff, '/admin/skus/' + product.id + '/stock', 'POST', {
      expectedVersion: 0, delta: 100, requestKey: 'forbidden-stock-' + suffix
    });
    check('staff_direct_calls_cannot_change_owner_configuration_or_stock',
      (await request(owner, '/admin/delivery-rules')).find(r => r.campus_id === campus).fee === 4.5);
    const foreignMerchant = (await request(platform, '/platform/merchants')).find(m => m.id !== merchant.id);
    assert.ok(foreignMerchant, 'A different merchant must exist for tenant-header checks');
    await forbidden(staff, '/admin/commerce-orders', 'GET', undefined, { 'X-Merchant-Id': String(foreignMerchant.id) });
    check('staff_direct_call_cannot_select_another_merchant', true);
    for (const name of ['确认接单', '开始制作', '备餐完成']) {
      await mutate(staffPage, () => staffPage.locator('#commerceManagement').getByRole('button', { name, exact: true }).click(), '/admin/commerce-orders/' + order.id + '/transition');
    }
    check('staff_can_complete_campus_preparation', (await request(customer, '/checkouts/' + checkout.id, 'GET', undefined, false)).orders[0].fulfillment_status === 'READY');

    const rider = await browser.newContext();
    await request(rider, '/admin/login', 'POST', { username: riderNames[0], password });
    const riderPage = await open(rider, '/rider/');
    const relayContext = await browser.newContext();
    await request(relayContext, '/admin/login', 'POST', { username: riderNames[1], password });
    const relayPage = await open(relayContext, '/rider/');
    // Keep the second rider's visible WAITING card to exercise a stale competing claim.
    await mutate(riderPage, () => riderPage.getByRole('button', { name: '抢单', exact: true }).click(), '/claim');
    const task = (await request(rider, '/delivery-tasks'))[0];
    await mutate(relayPage, () => relayPage.getByRole('button', { name: '抢单', exact: true }).click(), '/claim', 'POST', 400);
    check('competing_rider_claim_is_rejected_without_reassignment',
      (await relayPage.locator('#notice.error').innerText()).includes('任务已被接走')
      && !(await request(relayContext, '/delivery-tasks')).some(t => t.id === task.id)
      && (await request(rider, '/delivery-tasks')).find(t => t.id === task.id).status === 'ASSIGNED');
    const platformPage = await open(platform, '/platform/');
    const reason = '车辆故障，需要平台安排接续骑手';
    await riderPage.locator('[name=reason]').fill(reason);
    await mutate(riderPage, () => riderPage.getByRole('button', { name: '报告异常', exact: true }).click(), '/transition');
    const tasksResponse = platformPage.waitForResponse(r => r.url().endsWith('/platform/delivery-tasks'), { timeout: 3000 }).then(() => true, () => false);
    await platformPage.locator('#refresh').click();
    check('platform_main_refresh_updates_campus_tasks', await tasksResponse);
    await mutate(platformPage, () => platformPage.getByRole('button', { name: '刷新校园业务', exact: true }).click(), '/platform/delivery-tasks', 'GET');
    const taskCard = panel(platformPage, '任务 ' + task.id + ' · 配送异常');
    const taskText = await taskCard.innerText();
    check('platform_task_identifies_order_shop_and_destination', taskText.includes('店内订单 #' + order.id) && taskText.includes(merchant.name) && taskText.includes('体验校区' + suffix));
    check('platform_can_read_rider_exception_reason', taskText.includes(reason));
    const relay = (await request(platform, '/platform/riders')).find(r => r.username === riderNames[1]);
    await taskCard.locator('select').selectOption(String(relay.id));
    await taskCard.locator('[name=reason]').fill('安排接续配送');
    await mutate(platformPage, () => taskCard.getByRole('button', { name: '指派或改派' }).click(), '/assign');
    await mutate(relayPage, () => relayPage.locator('#refresh').click(), '/delivery-tasks', 'GET');
    await mutate(relayPage, () => relayPage.getByRole('button', { name: '已取餐', exact: true }).click(), '/transition');
    const code = await request(customer, '/checkouts/orders/' + order.id + '/code', 'GET', undefined, false);
    await relayPage.locator('[name=code]').fill(code === '000000' ? '111111' : '000000');
    await mutate(relayPage, () => relayPage.getByRole('button', { name: '核验送达', exact: true }).click(), '/transition', 'POST', 409);
    check('wrong_delivery_code_is_visible_and_recoverable', await relayPage.locator('#notice.error').isVisible() && await relayPage.locator('[name=code]').isVisible());
    await relayPage.locator('[name=code]').fill(code);
    await mutate(relayPage, () => relayPage.getByRole('button', { name: '核验送达', exact: true }).click(), '/transition');
    check('reassigned_delivery_completes_with_customer_code', (await request(customer, '/checkouts/' + checkout.id, 'GET', undefined, false)).orders[0].fulfillment_status === 'COMPLETED');

    const customerPage = await open(customer, '/campus/');
    const customerOrder = () => customerPage.locator('[data-checkout-id="' + checkout.id + '"]');
    const feedbackMessages = ['店主反馈体验 ' + suffix, '平台反馈体验 ' + suffix];
    for (const [index, feedbackMessage] of feedbackMessages.entries()) {
      const feedbackForm = customerOrder().locator('form').filter({ has: customerPage.getByRole('button', { name: '提交反馈', exact: true }) });
      await feedbackForm.locator('[name=message]').fill(feedbackMessage);
      await mutate(customerPage, () => feedbackForm.getByRole('button', { name: '提交反馈', exact: true }).click(), '/feedback');
      const managementPage = index === 0 ? ownerPage : platformPage;
      await mutate(managementPage, () => managementPage.getByRole('button', { name: '刷新校园业务', exact: true }).click(), '/feedback', 'GET');
      const feedbackCard = panel(managementPage, feedbackMessage), reply = (index === 0 ? '店主已核实并回复 ' : '平台已跟进并回复 ') + suffix;
      await feedbackCard.locator('[name=reply]').fill(reply);
      await mutate(managementPage, () => feedbackCard.getByRole('button', { name: '回复', exact: true }).click(), '/reply');
      await mutate(customerPage, () => customerPage.locator('#refresh').click(), '/feedback', 'GET');
      check((index === 0 ? 'owner' : 'platform') + '_feedback_reply_is_visible_to_customer',
        (await customerPage.locator('#feedbackList').innerText()).includes(feedbackMessage + ' · ' + reply));
    }
    const refundReason = '配送完成后的售后争议 ' + suffix;
    const refundForm = customerOrder().locator('form').filter({ has: customerPage.getByRole('button', { name: '提交按数量退款', exact: true }) });
    await refundForm.locator('input[type=number]').fill('1');
    await refundForm.locator('[name=reason]').fill(refundReason);
    await mutate(customerPage, () => refundForm.getByRole('button', { name: '提交按数量退款', exact: true }).click(), '/aftersales');
    await mutate(ownerPage, () => ownerPage.getByRole('button', { name: '刷新校园业务', exact: true }).click(), '/admin/aftersales', 'GET');
    const ownerRefund = panel(ownerPage, '售后审核').locator('article').filter({ hasText: refundReason });
    await ownerRefund.locator('[name=reason]').fill('店铺暂不同意，顾客可提交平台核验');
    await mutate(ownerPage, () => ownerRefund.getByRole('button', { name: '驳回申请', exact: true }).click(), '/decision');
    await mutate(customerPage, () => customerPage.locator('#refresh').click(), '/feedback', 'GET');
    const dispute = customerOrder().locator('form').filter({ has: customerPage.getByRole('button', { name: '提交平台申诉', exact: true }) });
    await dispute.locator('[name=reason]').fill('请求平台核验本单售后 ' + suffix);
    await mutate(customerPage, () => dispute.getByRole('button', { name: '提交平台申诉', exact: true }).click(), '/dispute');
    const aftersale = (await request(customer, '/aftersales', 'GET', undefined, false)).find(a => a.order_id === order.id);
    check('customer_can_escalate_rejected_aftersale_to_platform', aftersale.status === 'DISPUTED');
    await forbidden(owner, '/admin/aftersales/' + aftersale.id + '/decision', 'POST', { approve: true, reason: '店铺不能处理平台争议' });
    check('merchant_cannot_decide_a_disputed_aftersale', true);
    await mutate(platformPage, () => platformPage.getByRole('button', { name: '刷新校园业务', exact: true }).click(), '/platform/aftersales', 'GET');
    const platformRefund = panel(platformPage, '售后审核').locator('article').filter({ hasText: refundReason });
    await platformRefund.locator('[name=reason]').fill('平台核验后同意本单模拟退款');
    await mutate(platformPage, () => platformRefund.getByRole('button', { name: '同意退款', exact: true }).click(), '/decision');
    await mutate(customerPage, () => customerPage.locator('#refresh').click(), '/feedback', 'GET');
    const finalOrder = (await request(customer, '/checkouts/' + checkout.id, 'GET', undefined, false)).orders[0];
    check('platform_dispute_decision_and_refund_are_visible_to_customer',
      (await customerOrder().innerText()).includes('已退款') && Number(finalOrder.refunded_amount) === 20
      && (await request(customer, '/aftersales', 'GET', undefined, false)).find(a => a.id === aftersale.id).status === 'APPROVED');
    for (const [name, page] of [['customer', customerPage], ['owner', ownerPage], ['staff', staffPage], ['platform', platformPage], ['rider', relayPage]]) {
      await page.setViewportSize({ width: 390, height: 844 });
      check(name + '_mobile_has_no_horizontal_overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      if (name === 'owner') await panel(page, '所属校区与跨校区配送').scrollIntoViewIfNeeded();
      if (name === 'platform') await panel(page, '配送任务与改派').scrollIntoViewIfNeeded();
      fs.mkdirSync(path.dirname(output), { recursive: true });
      await page.screenshot({ path: path.join(path.dirname(output), 'role-' + name + '.png') });
    }
    check('no_role_browser_script_errors', errors.length === 0);
  } catch (error) {
    checks.push({ name: 'execution', passed: false, detail: error.message });
    for (const context of browser.contexts()) for (const page of context.pages()) {
      const notice = await page.locator('#notice').innerText().catch(() => 'unavailable');
      console.error(new URL(page.url()).pathname + ' notice: ' + notice);
    }
    throw error;
  } finally {
    await browser.close();
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, JSON.stringify({ passed: checks.every(c => c.passed), checks, errors }, null, 2));
  }
  if (checks.some(c => !c.passed)) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });
