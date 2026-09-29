"""Exercise the running Compose HTTP contract with an isolated test account.

Creates a disposable account, confirms one order, and cancels that order.
Reports contain assertions and timings, never credentials or user content.
"""
import argparse
import json
import secrets
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Barrier
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:9090')
    parser.add_argument('--workers', type=int, default=16)
    parser.add_argument('--report', default='load/results/functional-concurrency.json')
    args = parser.parse_args()
    headers = {'Content-Type': 'application/json'}
    checks = []
    order_seq = None
    passed = False

    def request(method, path, body=None, extra=None):
        data = None if body is None else json.dumps(body).encode()
        req = Request(args.base_url + path, data=data, method=method,
                      headers={**headers, **(extra or {})})
        try:
            with urlopen(req, timeout=45) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def ok(method, path, body=None, extra=None):
        status, result = request(method, path, body, extra)
        assert status == 200 and result['code'] == 1, f'{method} {path}: {status}, code={result.get("code")}'
        return result.get('data')

    def check(name, condition):
        checks.append({'check': name, 'passed': bool(condition)})
        assert condition, name

    def burst(action):
        barrier = Barrier(args.workers)
        def run(i):
            barrier.wait(timeout=20)
            start = time.monotonic()
            value = action(i)
            return value, round((time.monotonic() - start) * 1000, 1)
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            return list(pool.map(run, range(args.workers)))

    started = time.monotonic()
    timings = {}
    try:
        account = ok('POST', '/auth/register', {
            'username': 'verify_' + secrets.token_hex(5),
            'password': secrets.token_urlsafe(24), 'nickname': 'Concurrency verification'})
        headers['Authorization'] = 'Bearer ' + account['token']
        status, _ = request('POST', '/order/place', {'items': [{'dishId': 1, 'quantity': 1}]})
        check('direct_order_endpoint_removed', status == 405)
        for message in ['我对花生过敏', '我有过敏但不确定是什么']:
            ok('POST', '/chat', {'message': message, 'history': []})
            context = ok('GET', '/order/safety-context')
            check('allergy_requires_explicit_selection', context['needsClarification'])
            _, rejected = request('POST', '/order/drafts', {'items': [{'dishId': 1, 'quantity': 1}]})
            check('unresolved_allergy_blocks_draft', rejected['code'] != 1)
            ok('DELETE', '/order/safety-context')
        ok('PUT', '/order/safety-context', {'allergens': ['花生']})
        check('temporary_constraint_persists', ok('GET', '/order/safety-context')['allergens'] == ['花生'])
        ok('DELETE', '/order/safety-context')
        check('temporary_constraint_clears', not ok('GET', '/order/safety-context')['allergens'])
        drafts = burst(lambda i: ok('POST', '/order/drafts', {
            'items': [{'dishId': 1, 'quantity': 1}], 'remark': 'HTTP concurrency verification'}))
        timings['createMs'] = [duration for _, duration in drafts]
        pending = ok('GET', '/order/drafts/pending')
        check('one_active_draft_after_concurrent_creation', len(pending) == 1)
        draft_id = pending[0]['id']
        restaurant_now = lambda: datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None)
        seconds_left = (datetime.fromisoformat(pending[0]['expiresAt']) - restaurant_now()).total_seconds()
        check('draft_expires_in_five_minutes', 240 < seconds_left <= 301)
        confirmed = burst(lambda i: ok('POST', f'/order/drafts/{draft_id}/confirm',
                                      extra={'Idempotency-Key': 'verify-' + secrets.token_hex(16)}))
        timings['confirmMs'] = [duration for _, duration in confirmed]
        order_seq = confirmed[0][0]['userSeq']
        created = datetime.fromisoformat(confirmed[0][0]['createTime'])
        check('order_time_matches_restaurant_clock', abs((created - restaurant_now()).total_seconds()) < 30)
        check('all_confirmations_return_same_order', len({value['id'] for value, _ in confirmed}) == 1)
        check('one_real_order_created', ok('GET', '/order/list')['total'] == 1)
        check('confirmed_draft_no_longer_pending', len(ok('GET', '/order/drafts/pending')) == 0)
        cancelled = ok('POST', f'/order/{order_seq}/cancel')
        check('test_order_cancelled', cancelled['status'] == 5)
        order_seq = None
        passed = True
    finally:
        if order_seq is not None:
            ok('POST', f'/order/{order_seq}/cancel')
        report = {'passed': passed, 'workers': args.workers, 'checks': checks, 'timings': timings,
                  'elapsedSeconds': round(time.monotonic() - started, 2)}
        path = Path(args.report)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
