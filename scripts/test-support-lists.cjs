// Real browser modules; HTTP fixtures isolate the list navigation and mutation UI.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.argv[2] || 'playwright');
const root = path.resolve(__dirname, '../java-gateway/src/main/resources/static');

(async () => {
  const browser = await chromium.launch({headless: true,
    ...(process.argv[3] ? {executablePath: process.argv[3]} : {})});
  const checks = [];
  try {
    for (const role of ['STAFF', 'PLATFORM_ADMIN', 'CUSTOMER']) {
      const page = await browser.newPage(), errors = [], reads = [];
      const customer = role === 'CUSTOMER', base = role === 'PLATFORM_ADMIN' ? '/platform' : '/admin';
      const data = Object.fromEntries(['aftersales', 'feedback'].map(kind => [kind,
        Object.fromEntries(['active', 'history'].map(scope => [scope,
          Array.from({length: 51}, (_, i) => ({id: i + (scope === 'active' ? 1 : 101),
            reason: '售后事项' + i, message: '反馈事项' + i, amount: 1, units: [],
            reply: scope === 'history' ? '已回复' : null,
            status: kind === 'feedback' ? (scope === 'active' ? 'OPEN' : 'CLOSED')
              : (scope === 'active' ? 'PENDING' : 'APPROVED')}))]))]));
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), p = url.pathname;
        const json = value => route.fulfill({contentType: 'application/json', body: JSON.stringify({code: 1, data: value})});
        if (p === '/campus/' || p.startsWith('/assets/'))
          return route.fulfill({body: fs.readFileSync(path.join(root, p === '/campus/' ? 'campus/index.html' : p)),
            contentType: p.endsWith('.js') ? 'text/javascript' : p.endsWith('.css') ? 'text/css' : 'text/html'});
        if (p === '/fixture/') return route.fulfill({contentType: 'text/html', body:
          '<div id="notice"></div><div id="merchants"></div><div id="workspace"><nav class="task-nav"></nav></div><script type="module" src="/fixture.js"></script>'});
        if (p === '/fixture.js') return route.fulfill({contentType: 'text/javascript', body:
          `import {state} from '/assets/api.js'; import {mountCommerce} from '/assets/commerce-admin.js'; state.me={role:'${role}'}; await mountCommerce();`});
        if (p === '/auth/me') return json({userId: 1, role, username: 'fixture'});
        if (p === '/auth/csrf') return json({token: 'fixture'});
        if (p === '/campuses') return json([{id: 1, name: '测试校区'}]);
        if (p === '/me/events') return route.fulfill({status: 403, body: ''});
        const mutation = p.match(/\/(aftersales|feedback)\/(\d+)\/(decision|reply)$/);
        if (mutation) {
          const [, kind, id] = mutation, rows = data[kind];
          const index = rows.active.findIndex(row => row.id === Number(id));
          assert.ok(index >= 0);
          const [record] = rows.active.splice(index, 1);
          rows.history.unshift({...record, status: kind === 'feedback' ? 'CLOSED' : 'APPROVED', reply: '已回复'});
          return json(null);
        }
        const kind = p.endsWith('/aftersales') ? 'aftersales' : p.endsWith('/feedback') ? 'feedback' : null;
        if (kind) {
          const scope = url.searchParams.get('scope'), number = Number(url.searchParams.get('page'));
          assert.equal(url.searchParams.get('pageSize'), '50');
          assert.ok(['all', 'active', 'history'].includes(scope));
          reads.push({kind, scope, page: number});
          const rows = scope === 'all' ? [...data[kind].active, ...data[kind].history] : data[kind][scope];
          return json(rows.slice((number - 1) * 50, number * 50));
        }
        return json([]);
      });
      await page.goto('http://127.0.0.1:19876/' + (customer ? 'campus/' : 'fixture/'));
      for (const kind of customer ? ['feedback'] : ['aftersales', 'feedback']) {
        const label = customer ? '我的反馈' : kind === 'aftersales' ? '售后' : '反馈';
        const filter = page.getByLabel(label + '列表范围');
        const controls = page.locator('.list-pagination').filter({has: filter});
        await filter.waitFor();
        assert.equal(await filter.inputValue(), customer ? 'all' : 'active');
        await controls.getByRole('button', {name: '下一页', exact: true}).click();
        await controls.getByText('第 2 页 · 每页 50 条', {exact: true}).waitFor();
        assert.equal(reads.filter(r => r.kind === kind).at(-1).page, 2);
        if (!customer) {
          await page.getByRole('button', {name: '刷新校园业务', exact: true}).click();
          await controls.getByText('第 2 页 · 每页 50 条', {exact: true}).waitFor();
        }
        await filter.selectOption('history');
        await controls.getByText('第 1 页 · 每页 50 条', {exact: true}).waitFor();
        await controls.getByRole('button', {name: '下一页', exact: true}).click();
        await controls.getByText('第 2 页 · 每页 50 条', {exact: true}).waitFor();
        assert.equal(await controls.getByRole('button', {name: '下一页', exact: true}).isDisabled(), true);
        await filter.selectOption('active');
        await controls.getByText('第 1 页 · 每页 50 条', {exact: true}).waitFor();
        if (!customer) {
          const panel = page.locator('#commerceManagement article').filter({has: filter});
          const form = panel.locator('form').first();
          await form.locator('input').fill('确认处理');
          const previousCount = data[kind].active.length;
          await form.getByRole('button', {name: kind === 'aftersales' ? '同意退款' : '回复', exact: true}).click();
          await page.waitForFunction(() => document.querySelector('#notice').textContent === '操作完成');
          assert.equal(data[kind].active.length, previousCount - 1);
          assert.equal(await filter.inputValue(), 'active');
        }
        checks.push(role + ': ' + kind + ' default, bounded pages, history reset, processing refresh');
      }
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log(JSON.stringify({passed: true, checks}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
