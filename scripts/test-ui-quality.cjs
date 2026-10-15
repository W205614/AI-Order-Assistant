// Responsive and keyboard acceptance for the local, disposable campus deployment.
// Optional fixture fields: username, password, campusId, addressId, ownerUsername, riderUsername.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const options = Object.fromEntries(process.argv.slice(2).reduce((all, value, index, args) => {
  if (value.startsWith('--')) all.push([value.slice(2), args[index + 1]]);
  return all;
}, []));
const base = options['base-url'] || 'http://127.0.0.1:19090';
const target = new URL(base);
assert.ok(['localhost', '127.0.0.1'].includes(target.hostname) && ['19090', '19092'].includes(target.port), 'Use a disposable local test port');
const { chromium } = require(options['playwright-module'] || 'playwright');
const report = options.report || '.local/ui-quality/results.json';
const directory = path.dirname(report);
fs.mkdirSync(directory, { recursive: true });
const fixture = options.fixture ? JSON.parse(fs.readFileSync(options.fixture, 'utf8')) : null;
assert.ok(!options['model-message'] || fixture, '--model-message requires an authenticated customer fixture');
if (fixture && !fixture.ownerUsername) fixture.ownerUsername = fixture.stores?.[0]?.ownerUsername;
const env = Object.fromEntries(fs.readFileSync(options['env-file'] || '.github/compose-ci.env', 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const checks = [], issues = [], screenshots = [], scriptErrors = [], modelObservations = [];
let trustedQuote;
function check(name, condition, detail) {
  checks.push({ name, passed: Boolean(condition), ...(detail ? { detail } : {}) });
  if (!condition) issues.push({ name, detail });
  console.log((condition ? 'Passed: ' : 'Failed: ') + name);
}
async function login(context, username, password, management) {
  const csrf = await context.request.get(base + '/auth/csrf');
  const response = await context.request.post(base + (management ? '/admin/login' : '/auth/login'), {
    headers: { 'X-XSRF-TOKEN': (await csrf.json()).data.token, ...(management ? { 'X-Session-Type': 'management' } : {}) },
    data: { username, password },
  });
  const result = await response.json();
  assert.equal(response.status(), 200, 'Fixture login: ' + result.msg);
  assert.equal(result.code, 1, 'Fixture login: ' + result.msg);
}
async function customerLoginThroughUi(context) {
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'customer-login', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#login input[name=username]').fill(fixture.username);
  await page.locator('#login input[name=password]').fill(fixture.password);
  await page.locator('#login button').click();
  await page.locator('#workspace').waitFor({ state: 'visible' });
  check('customer_can_login_through_visible_form', await page.locator('#account').isVisible() && !await page.locator('#auth').isVisible());
  await page.close();
}
async function inspect(context, route, state) {
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route, state, message: error.message }));
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const navigation = await page.goto(base + route);
  check(`${route}_${state}_returns_html`, navigation.status() === 200 && navigation.headers()['content-type']?.includes('text/html'), { status: navigation.status(), contentType: navigation.headers()['content-type'] });
  if (route !== '/') await page.locator(state === 'anonymous' ? '#login button' : '#workspace').waitFor({ state: 'visible' });
  if (route !== '/' && state === 'anonymous') await page.waitForFunction(() => !document.querySelector('#login button').disabled);
  if (route === '/campus/' && state === 'customer') {
    if (fixture?.campusId) await page.locator('#campus').selectOption(String(fixture.campusId));
    if (fixture?.addressId) await page.locator('#address').selectOption(String(fixture.addressId));
    if (fixture?.platformCoupon) await page.locator('#platformCoupon').selectOption(String(fixture.platformCoupon));
    if (fixture?.shopCoupon && fixture?.stores?.[0]) {
      const shop = page.locator('#campusCart article').filter({ has: page.locator('input[data-merchant="' + fixture.stores[0].id + '"]') });
      await shop.locator('select').first().selectOption(String(fixture.shopCoupon));
    }
  }
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const result = await page.evaluate(() => {
      const visible = el => el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })
        && ![...el.parentElement?.closest('details:not([open])')?.children || []]
          .some(child => child.tagName !== 'SUMMARY' && (child === el || child.contains(el)));
      const controls = [...document.querySelectorAll('input:not([type=hidden]),select,textarea')].filter(visible);
      const unnamed = controls.filter(el => !el.labels?.length && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby'))
        .map(el => ({ tag: el.tagName, id: el.id, name: el.name, placeholder: el.getAttribute('placeholder') }));
      const overflow = [...document.querySelectorAll('main,header,section,article,form,input,select,textarea,button,nav')]
        .filter(visible).filter(el => el.getBoundingClientRect().right > innerWidth + 2 || el.getBoundingClientRect().left < -2)
        .slice(0, 12).map(el => ({ tag: el.tagName, id: el.id, text: el.textContent.slice(0, 70) }));
      const links = [...document.querySelectorAll('a[href]')].filter(visible);
      const brokenAnchors = links.filter(el => el.getAttribute('href').startsWith('#') && !document.getElementById(el.getAttribute('href').slice(1)))
        .map(el => el.getAttribute('href'));
      return { width: document.documentElement.scrollWidth, unnamed, overflow, brokenAnchors };
    });
    const name = route.replaceAll('/', '') || 'home';
    check(`${name}_${state}_${width}_no_horizontal_overflow`, result.width <= width + 2 && result.overflow.length === 0, result.overflow);
    check(`${name}_${state}_${width}_named_form_controls`, result.unnamed.length === 0, result.unnamed);
    check(`${name}_${state}_${width}_reachable_anchors`, result.brokenAnchors.length === 0, result.brokenAnchors);
    await page.evaluate(() => scrollTo(0, 0));
    const screenshot = path.join(directory, `${name}-${state}-${width}.png`);
    await page.screenshot({ path: screenshot, fullPage: false });
    screenshots.push(screenshot);
    if (state !== 'anonymous' && width !== 768) {
      const sections = route === '/campus/' ? ['#browse', '#assistantSection', '#ordersSection']
        : route === '/chat/' ? ['#checkout'] : route === '/rider/' ? ['#tasks'] : ['#commerceManagement'];
      for (const section of sections) {
        await page.locator(section).scrollIntoViewIfNeeded();
        const sectionShot = path.join(directory, `${name}-${state}-${width}-${section.slice(1)}.png`);
        await page.screenshot({ path: sectionShot, fullPage: false });
        screenshots.push(sectionShot);
      }
    }
  }
  // Verify actual keyboard focus reaches visible controls and has a visible indicator.
  await page.evaluate(() => document.activeElement?.blur());
  await page.keyboard.press('Control+Home');
  for (let index = 0; index < 5; index++) {
    await page.keyboard.press('Tab');
    const focus = await page.evaluate(() => {
      const el = document.activeElement, style = getComputedStyle(el), bounds = el.getBoundingClientRect();
      return { tag: el.tagName, visible: bounds.width > 0 && bounds.height > 0, indicator: (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) || style.boxShadow !== 'none' };
    });
    check(`${route}_${state}_keyboard_focus_${index}`, focus.visible && focus.indicator, focus);
  }
  if (route !== '/') {
    await page.setViewportSize({ width: 390, height: 900 });
    const disclosures = page.locator(state === 'anonymous' ? '#auth details' : '#account details, #commerceManagement details');
    for (const disclosure of await disclosures.all()) await disclosure.locator('summary').first().click();
    const expanded = await page.evaluate(() => {
      const visible = el => el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
      const unnamed = [...document.querySelectorAll('input:not([type=hidden]),select,textarea')].filter(visible)
        .filter(el => !el.labels?.length && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby'))
        .map(el => ({ id: el.id, name: el.name }));
      const overflowing = [...document.querySelectorAll('form,input,select,textarea,button')].filter(visible)
        .filter(el => !el.closest('.table-wrap') && (el.getBoundingClientRect().right > innerWidth + 2 || el.getBoundingClientRect().left < -2))
        .slice(0, 12).map(el => ({ tag: el.tagName, name: el.name, text: el.textContent.slice(0, 50) }));
      return { unnamed, overflowing, width: document.documentElement.scrollWidth };
    });
    check(`${route}_${state}_expanded_mobile_controls_named`, expanded.unnamed.length === 0, expanded.unnamed);
    check(`${route}_${state}_expanded_mobile_no_overflow`, expanded.width <= 392 && expanded.overflowing.length === 0, expanded.overflowing);
    for (const disclosure of await disclosures.all()) await disclosure.locator('summary').first().click();
  }
  if (route === '/campus/' && state === 'customer') {
    if (await page.locator('#campusCart article').count()) {
      const [response] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/checkouts/quotes') && response.request().method() === 'POST'),
        page.locator('#quoteButton').click(),
      ]);
      const body = await response.json();
      await page.waitForFunction(() => !document.querySelector('#quoteButton').disabled);
      check('customer_quote_produces_visible_result', response.status() === 200 && body.code === 1 && await page.locator('#quote').innerText().then(text => text.includes('本次合计')));
      if (fixture.expectedTotal !== undefined) check('customer_stacked_coupon_quote_matches_fixture_total', Number(body.data?.total) === Number(fixture.expectedTotal));
      if (response.status() === 200 && body.code === 1 && !Object.keys(body.data.unavailable).length) trustedQuote = body.data;
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.locator('#quote').screenshot({ path: path.join(directory, 'campus-java-quote-detail-desktop.png') });
      await page.setViewportSize({ width: 390, height: 900 });
      await page.locator('#quote').screenshot({ path: path.join(directory, 'campus-java-quote-detail-mobile.png') });
      if (!options['model-message'] && trustedQuote) {
        check('customer_valid_quote_requires_explicit_confirmation', await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 1);
        const otherCampus = await page.locator('#campus option').evaluateAll((options, current) => options.find(option => option.value !== current)?.value, String(fixture.campusId));
        if (otherCampus) {
          await page.locator('#campus').selectOption(otherCampus);
          check('changing_campus_invalidates_existing_quote', await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 0);
          await page.locator('#campus').selectOption(String(fixture.campusId));
        }
        await page.locator('#mode').selectOption('PICKUP');
        check('pickup_does_not_require_address_and_invalidates_delivery_quote', await page.locator('#address').isDisabled() && await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 0);
        await page.locator('#mode').selectOption('DELIVERY');
        check('returning_to_delivery_requires_a_new_quote', await page.locator('#address').isEnabled() && await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 0);
      }
    } else {
      await page.locator('#quoteButton').click();
      await page.waitForFunction(() => !document.querySelector('#quoteButton').disabled);
      check('empty_cart_quote_shows_actionable_error', await page.locator('#notice').innerText().then(text => text.includes('请勾选需要结算的店铺')));
    }
    check('customer_can_reach_ai_and_checkout', await page.locator('#campusChat').count() === 1 && await page.locator('#checkoutList').count() === 1);
    if (options['model-message']) {
      const checkoutIds = async () => {
        const response = await context.request.get(base + '/checkouts?page=1&pageSize=20');
        assert.equal(response.status(), 200);
        return (await response.json()).data.map(checkout => checkout.id).sort((a, b) => a - b);
      };
      const beforeCheckouts = await checkoutIds();
      const cartSnapshot = async () => {
        const response = await context.request.get(base + '/cart');
        assert.equal(response.status(), 200);
        return (await response.json()).data.map(item => ({ id: item.id, productId: item.product_id, quantity: item.quantity, version: item.version })).sort((a, b) => a.id - b.id);
      };
      const beforeCart = await cartSnapshot();
      await page.locator('#campusChat textarea').fill(options['model-message']);
      const [response] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/campus-assistant/chat') && response.request().method() === 'POST', { timeout: 120000 }),
        page.locator('#campusChat button').click(),
      ]);
      const body = await response.json();
      await page.waitForFunction(() => !document.querySelector('#campusChat button').disabled, null, { timeout: 120000 });
      const reply = body.data?.reply;
      check('real_model_reply_is_rendered', response.status() === 200 && body.code === 1 && Boolean(reply) && await page.locator('#campusMessages').innerText().then(text => text.includes(reply)));
      check('real_model_completed_without_degraded_fallback', body.data?.outcome === 'completed');
      check('real_model_returns_persisted_java_quote', Boolean(body.data?.quote?.id));
      const usage = body.data?.modelUsage;
      check('real_model_has_provider_token_usage', usage?.inputTokens > 0 && usage?.outputTokens > 0 && usage?.modelCalls > 0);
      check('real_model_uses_customer_facing_copy', !/cartVersion|productVersion|quoteId|merchantId|购物车版本|商品版本|报价单号|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}/i.test(reply || '') && !/\*\*|^\s*\|.+\|/m.test(reply || ''));
      modelObservations.push({ status: response.status(), outcome: body.data?.outcome, hasQuote: Boolean(body.data?.quote), contextApplied: body.data?.contextApplied, replyLength: reply?.length || 0, modelUsage: usage });
      if (body.data?.quote) {
        trustedQuote = body.data.quote;
        check('real_model_persisted_quote_is_rendered', await page.locator('#quote').innerText().then(text => text.includes('本次合计')));
        check('real_model_quote_requires_explicit_customer_confirmation', await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 1);
        const request = body.data.quote.request;
        check('real_model_quote_context_matches_customer_controls', Number(await page.locator('#campus').inputValue()) === request.campusId && await page.locator('#mode').inputValue() === request.fulfillmentMode && (request.fulfillmentMode === 'PICKUP' || Number(await page.locator('#address').inputValue()) === request.addressId));
        check('real_model_quote_coupon_matches_customer_control', (await page.locator('#platformCoupon').inputValue() || '') === String(request.platformCoupon || ''));
        if (options['expected-quote-total']) check('real_model_quote_has_expected_actual_total', Number(body.data.quote.total) === Number(options['expected-quote-total']));
      }
      check('real_model_preparation_does_not_create_checkout', JSON.stringify(beforeCheckouts) === JSON.stringify(await checkoutIds()));
      check('real_model_read_only_request_preserves_cart', JSON.stringify(beforeCart) === JSON.stringify(await cartSnapshot()));
      await page.locator('#assistantSection').screenshot({ path: path.join(directory, 'campus-real-model-reply-mobile.png') });
      await page.locator('#cartSection').screenshot({ path: path.join(directory, 'campus-real-model-quote-mobile.png') });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.locator('#quote').scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(directory, 'campus-real-model-quote-desktop.png') });
      await page.locator('#quote').screenshot({ path: path.join(directory, 'campus-real-model-quote-detail-desktop.png') });
    }
  }
  if (route === '/campus/' && state === 'anonymous') {
    await page.locator('#login input[name=username]').fill('ui_nonexistent_account');
    await page.locator('#login input[name=password]').fill('UI-invalid-password-123');
    const [response] = await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST'),
      page.locator('#login button').click(),
    ]);
    await page.waitForFunction(() => !document.querySelector('#login button').disabled);
    check('invalid_login_shows_inline_error_and_preserves_form', response.status() === 401 && await page.locator('#notice.error').count() === 1 && await page.locator('#auth').isVisible());
  }
  await page.close();
}
async function inspectInitializationRecovery(context) {
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'recovery', message: error.message }));
  // This deliberately simulates one unavailable optional endpoint to test client recovery.
  // It is not evidence of real backend availability or provider behavior.
  await page.route('**/favorites', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 0, errorCode: 'SERVICE_UNAVAILABLE', msg: '验收：收藏暂时不可用' }) }), { times: 1 });
  await page.goto(base + '/campus/');
  await page.locator('#notice.error').filter({ hasText: '验收：收藏暂时不可用' }).waitFor();
  check('failed_initialization_preserves_authenticated_account_and_recovery', await page.locator('#account').isVisible() && !await page.locator('#auth').isVisible() && !await page.locator('#workspace').isVisible() && await page.locator('#workspaceRecovery').isVisible());
  await page.locator('#workspaceRecovery').getByRole('button', { name: '重新加载工作区', exact: true }).click();
  await page.locator('#workspace').waitFor({ state: 'visible' });
  check('failed_initialization_can_retry_without_login', !await page.locator('#auth').isVisible() && !await page.locator('#workspaceRecovery').isVisible());
  await page.close();
}
async function inspectMenuSwitchRace(context) {
  if (!fixture.stores?.[1] || !fixture.products?.[1]) return;
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'menu-race', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#workspace').waitFor({ state: 'visible' });
  const campusShops = page.waitForResponse(response => response.url().endsWith('/campuses/' + fixture.campusId + '/merchants'));
  await page.locator('#campus').selectOption(String(fixture.campusId));
  await (await campusShops).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.locator('#shop').selectOption(String(fixture.stores[1].id));
  await page.locator('#campusMenu h3').filter({ hasText: fixture.products[1].name }).waitFor();
  let entered, release, finished;
  const pending = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  const delivered = new Promise(resolve => { finished = resolve; });
  // Delay one genuine menu response; no fake product data or backend mutation is used.
  await page.route('**/campus-menu/' + fixture.stores[0].id, async route => {
    const response = await route.fetch();
    entered();
    await gate;
    await route.fulfill({ response });
    finished();
  }, { times: 1 });
  const lateResponse = page.waitForResponse(response => response.url().endsWith('/campus-menu/' + fixture.stores[0].id));
  await page.locator('#shop').selectOption(String(fixture.stores[0].id));
  await Promise.race([pending, new Promise((_, reject) => setTimeout(() => reject(new Error('Expected delayed menu request')), 15000))]);
  check('pending_shop_menu_has_no_stale_add_to_cart_actions', await page.locator('#campusMenu').getByRole('button', { name: '加入购物车', exact: true }).count() === 0);
  const currentResponse = page.waitForResponse(response => response.url().endsWith('/campus-menu/' + fixture.stores[1].id));
  await page.locator('#shop').selectOption(String(fixture.stores[1].id));
  await (await currentResponse).finished();
  await page.locator('#campusMenu h3').filter({ hasText: fixture.products[1].name }).waitFor();
  release();
  await delivered;
  await (await lateResponse).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  check('late_menu_response_cannot_replace_current_shop', await page.locator('#campusMenu h3').filter({ hasText: fixture.products[1].name }).count() === 1 && await page.locator('#campusMenu h3').filter({ hasText: fixture.products[0].name }).count() === 0);
  await page.close();
}
async function inspectCampusShopSelectionRace(context, preserveCurrent) {
  if (!fixture.stores?.[1] || !fixture.products?.[1]) return;
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'campus-shop-race', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#workspace').waitFor({ state: 'visible' });
  const candidate = preserveCurrent ? String(fixture.stores[1].id) : await page.locator('#shop option').evaluateAll((options, excluded) => options.find(option => !excluded.includes(Number(option.value)))?.value, fixture.stores.map(store => store.id));
  assert.ok(candidate, 'Campus race fixture needs an available source-campus shop');
  let entered, release, finished;
  const pending = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  const delivered = new Promise(resolve => { finished = resolve; });
  await page.route('**/campuses/' + fixture.campusId + '/merchants', async route => {
    const response = await route.fetch();
    entered();
    await gate;
    await route.fulfill({ response });
    finished();
  }, { times: 1 });
  const campusResponse = page.waitForResponse(response => response.url().endsWith('/campuses/' + fixture.campusId + '/merchants'));
  await page.locator('#campus').selectOption(String(fixture.campusId));
  await Promise.race([pending, new Promise((_, reject) => setTimeout(() => reject(new Error('Expected delayed campus shop list')), 15000))]);
  check(preserveCurrent ? 'pending_campus_list_has_no_stale_menu_actions' : 'pending_fallback_campus_list_has_no_stale_menu_actions', await page.locator('#campusMenu').getByRole('button', { name: '加入购物车', exact: true }).count() === 0);
  await page.locator('#shop').selectOption(candidate);
  release();
  await delivered;
  await (await campusResponse).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const expectedIndex = preserveCurrent ? 1 : 0;
  const selectionMatches = await page.locator('#shop').inputValue() === String(fixture.stores[expectedIndex].id);
  check(preserveCurrent ? 'campus_refresh_preserves_latest_available_shop_selection' : 'campus_refresh_falls_back_when_selected_shop_is_unavailable', selectionMatches);
  if (!selectionMatches) { await page.close(); return; }
  await page.waitForFunction(expected => [...document.querySelectorAll('#campusMenu h3')].some(heading => heading.textContent === expected), fixture.products[expectedIndex].name);
  check(preserveCurrent ? 'retained_shop_menu_matches_current_selection' : 'fallback_shop_menu_replaces_unavailable_shop', await page.locator('#campusMenu h3').count() === 1 && await page.locator('#campusMenu h3').filter({ hasText: fixture.products[expectedIndex].name }).count() === 1);
  await page.close();
}
async function inspectCampusLoadingRecovery(context) {
  if (!fixture.stores?.[0] || !fixture.products?.[0]) return;
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'campus-loading-recovery', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#workspace').waitFor({ state: 'visible' });
  let entered, release;
  const pending = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  await page.route('**/addresses', async route => {
    const response = await route.fetch();
    entered();
    await gate;
    await route.fulfill({ response });
  }, { times: 1 });
  await page.route('**/campuses/' + fixture.campusId + '/merchants', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: '验收：店铺暂时不可用' }) }), { times: 1 });
  await page.locator('#campus').selectOption(String(fixture.campusId));
  await Promise.race([pending, new Promise((_, reject) => setTimeout(() => reject(new Error('Expected pending addresses during campus change')), 15000))]);
  check('campus_change_clears_old_menu_before_address_response', await page.locator('#campusMenu').getByRole('button', { name: '加入购物车', exact: true }).count() === 0);
  release();
  await page.locator('#notice.error').filter({ hasText: '验收：店铺暂时不可用' }).waitFor();
  const response = page.waitForResponse(response => response.url().endsWith('/campuses/' + fixture.campusId + '/merchants'));
  await page.locator('#refresh').click();
  await (await response).finished();
  await page.locator('#campusMenu h3').filter({ hasText: fixture.products[0].name }).waitFor();
  check('failed_campus_shop_list_can_retry_using_refresh', await page.locator('#campusMenu').getAttribute('aria-busy') === 'false' && await page.locator('#campusMenu').getByRole('button', { name: '加入购物车', exact: true }).count() > 0);
  await page.close();
}
async function inspectCampusListRace(context) {
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'campus-list-race', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#workspace').waitFor({ state: 'visible' });
  const sourceCampus = await page.locator('#campus').inputValue();
  const sourceShop = await page.locator('#shop').inputValue();
  const sourceMenu = await page.locator('#campusMenu h3').allTextContents();
  assert.notEqual(sourceCampus, String(fixture.campusId));
  let entered, release, finished;
  const pending = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  const delivered = new Promise(resolve => { finished = resolve; });
  await page.route('**/campuses/' + fixture.campusId + '/merchants', async route => {
    const response = await route.fetch(); entered(); await gate; await route.fulfill({ response }); finished();
  }, { times: 1 });
  const oldResponse = page.waitForResponse(response => response.url().endsWith('/campuses/' + fixture.campusId + '/merchants'));
  await page.locator('#campus').selectOption(String(fixture.campusId));
  await Promise.race([pending, new Promise((_, reject) => setTimeout(() => reject(new Error('Expected delayed first campus list')), 15000))]);
  const currentResponse = page.waitForResponse(response => response.url().endsWith('/campuses/' + sourceCampus + '/merchants'));
  await page.locator('#campus').selectOption(sourceCampus);
  await (await currentResponse).finished();
  await page.waitForFunction(expected => JSON.stringify([...document.querySelectorAll('#campusMenu h3')].map(el => el.textContent)) === JSON.stringify(expected), sourceMenu);
  release(); await delivered; await (await oldResponse).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  check('late_campus_shop_list_cannot_replace_current_campus', await page.locator('#campus').inputValue() === sourceCampus && await page.locator('#shop').inputValue() === sourceShop && JSON.stringify(await page.locator('#campusMenu h3').allTextContents()) === JSON.stringify(sourceMenu));
  await page.close();
}
async function inspectStaleAssistantQuote(context, delayedEndpoint) {
  assert.ok(trustedQuote, 'Race acceptance requires a genuine Java quote from this customer');
  const page = await context.newPage();
  page.on('pageerror', error => scriptErrors.push({ route: '/campus/', state: 'quote-race', message: error.message }));
  await page.goto(base + '/campus/');
  await page.locator('#workspace').waitFor({ state: 'visible' });
  await page.locator('#campus').selectOption(String(trustedQuote.request.campusId));
  if (trustedQuote.request.addressId) await page.locator('#address').selectOption(String(trustedQuote.request.addressId));
  await page.locator('#mode').selectOption(trustedQuote.request.fulfillmentMode);
  const originalCoupon = String(trustedQuote.request.platformCoupon || '');
  await page.locator('#platformCoupon').selectOption(originalCoupon);
  const alternative = await page.locator('#platformCoupon option').evaluateAll((options, original) => options.find(option => option.value !== original)?.value, originalCoupon);
  assert.notEqual(alternative, undefined, 'Race fixture needs an alternative platform coupon selection');
  // The model response is deliberately simulated here with a captured, genuine Java quote.
  // The separate --model-message path verifies a real provider response.
  await page.route('**/campus-assistant/chat', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 1, data: { reply: 'UI 竞态验收：请核对本次报价。', outcome: 'completed', contextApplied: true, quote: trustedQuote } }) }));
  let entered, release;
  const pending = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  await page.route('**' + delayedEndpoint, async route => {
    const response = await route.fetch();
    entered();
    await gate;
    await route.fulfill({ response });
  }, { times: 1 });
  await page.locator('#campusChat textarea').fill('UI 竞态验收，不调用真实模型。');
  await page.locator('#campusChat button').click();
  await Promise.race([pending, new Promise((_, reject) => setTimeout(() => reject(new Error('Expected delayed refresh: ' + delayedEndpoint)), 15000))]);
  await page.locator('#platformCoupon').selectOption(alternative);
  release();
  await page.waitForFunction(() => !document.querySelector('#campusChat button').disabled);
  check(`assistant_${delayedEndpoint}_preserves_new_coupon_selection`, await page.locator('#platformCoupon').inputValue() === alternative);
  check(`assistant_${delayedEndpoint}_does_not_restore_stale_quote`, await page.locator('#quote').getByRole('button', { name: '确认本次报价并创建订单', exact: true }).count() === 0);
  await page.close();
}
(async () => {
  const browser = await chromium.launch({ headless: true, ...(options['browser-path'] ? { executablePath: options['browser-path'] } : {}) });
  try {
    const anonymous = await browser.newContext();
    for (const route of ['/', '/campus/', '/chat/', '/admin/', '/platform/', '/rider/']) await inspect(anonymous, route, 'anonymous');
    const platform = await browser.newContext();
    await login(platform, env.PLATFORM_ADMIN_USERNAME, env.PLATFORM_ADMIN_PASSWORD, true);
    await inspect(platform, '/platform/', 'platform');
    if (fixture) {
      const customer = await browser.newContext();
      await customerLoginThroughUi(customer);
      await inspect(customer, '/campus/', 'customer');
      await inspect(customer, '/chat/', 'customer');
      await inspectInitializationRecovery(customer);
      await inspectCampusShopSelectionRace(customer, true);
      await inspectCampusShopSelectionRace(customer, false);
      await inspectMenuSwitchRace(customer);
      await inspectCampusLoadingRecovery(customer);
      await inspectCampusListRace(customer);
      if (trustedQuote) {
        await inspectStaleAssistantQuote(customer, '/coupons/mine');
        await inspectStaleAssistantQuote(customer, '/cart');
      }
      for (const [field, route, role] of [['ownerUsername', '/admin/', 'owner'], ['riderUsername', '/rider/', 'rider']]) {
        if (!fixture[field]) continue;
        const context = await browser.newContext();
        await login(context, fixture[field], fixture.password, true);
        await inspect(context, route, role);
      }
    }
    check('all_pages_render_without_script_errors', scriptErrors.length === 0, scriptErrors);
  } catch (error) {
    issues.push({ name: 'browser_execution_failed', detail: error.message });
    throw error;
  } finally {
    await browser.close();
    fs.writeFileSync(report, JSON.stringify({ passed: issues.length === 0, checks, issues, scriptErrors, modelObservations, screenshots }, null, 2));
  }
  if (issues.length) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });
