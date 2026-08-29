import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:9090';
const runWrites = (__ENV.RUN_WRITES || 'false').toLowerCase() === 'true';
const confirmWrites = (__ENV.RUN_CONFIRM || 'false').toLowerCase() === 'true';
const writeVus = Number(__ENV.WRITE_VUS || 2);

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${Number(__ENV.P95_THRESHOLD_MS || 10000)}`],
  },
  scenarios: {
    read_chat: { executor: 'constant-vus', vus: Number(__ENV.READ_VUS || 3), duration: __ENV.READ_DURATION || '30s', exec: 'readChat' },
    draft_flow: { executor: 'per-vu-iterations', vus: writeVus, iterations: 1, startTime: '35s', exec: 'draftFlow' },
  },
};

function jsonHeaders(token, extra = {}) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra } };
}

function login(username, password) {
  const response = http.post(`${baseUrl}/auth/login`, JSON.stringify({ username, password }), { headers: { 'Content-Type': 'application/json' } });
  check(response, { 'login succeeded': (r) => r.status === 200 && r.json('code') === 1 && r.json('data.token') });
  return response.json('data.token');
}

export function setup() {
  const demoToken = login(__ENV.EVAL_USERNAME || 'demo', __ENV.EVAL_PASSWORD || '123456');
  const writeTokens = [];
  if (runWrites) {
    const prefix = `k6-${Date.now()}`;
    for (let i = 0; i < writeVus; i += 1) {
      const username = `${prefix}-${i}`;
      const registered = http.post(`${baseUrl}/auth/register`, JSON.stringify({ username, password: 'K6-load-123', nickname: 'k6' }), { headers: { 'Content-Type': 'application/json' } });
      check(registered, { 'load user registered': (r) => r.status === 200 && r.json('code') === 1 });
      writeTokens.push(login(username, 'K6-load-123'));
    }
  }
  return { demoToken, writeTokens };
}

export function readChat(data) {
  const response = http.post(`${baseUrl}/chat`, JSON.stringify({ message: '今天菜单里有什么？', history: [] }), jsonHeaders(data.demoToken, { 'X-Request-Id': `k6-read-${__VU}-${__ITER}` }));
  check(response, { 'chat request succeeded': (r) => r.status === 200 && r.json('code') === 1 });
  sleep(1);
}

export function draftFlow(data) {
  if (!runWrites) return;
  const token = data.writeTokens[__VU - 1];
  const draft = http.post(`${baseUrl}/order/drafts`, JSON.stringify({ items: [{ dishName: '鱼香肉丝饭', quantity: 1 }], remark: 'k6 disposable environment' }), jsonHeaders(token));
  check(draft, { 'draft created': (r) => r.status === 200 && r.json('code') === 1 && r.json('data.id') });
  const draftId = draft.json('data.id');
  if (!draftId) return;
  if (!confirmWrites) {
    const cancelled = http.del(`${baseUrl}/order/drafts/${draftId}`, null, jsonHeaders(token));
    check(cancelled, { 'draft cancelled': (r) => r.status === 200 && r.json('code') === 1 });
    return;
  }
  const idempotencyKey = `k6-confirm-${__VU}-${Date.now()}`;
  const first = http.post(`${baseUrl}/order/drafts/${draftId}/confirm`, null, jsonHeaders(token, { 'Idempotency-Key': idempotencyKey }));
  const retry = http.post(`${baseUrl}/order/drafts/${draftId}/confirm`, null, jsonHeaders(token, { 'Idempotency-Key': idempotencyKey }));
  check(first, { 'draft confirmed': (r) => r.status === 200 && r.json('code') === 1 && r.json('data.id') });
  check(retry, { 'confirmation retry returns same order': (r) => r.status === 200 && r.json('data.id') === first.json('data.id') });
}

export function handleSummary(data) {
  const summaryPath = __ENV.K6_SUMMARY_PATH;
  const output = { stdout: JSON.stringify(data, null, 2) };
  if (summaryPath) output[summaryPath] = JSON.stringify(data, null, 2);
  return output;
}
