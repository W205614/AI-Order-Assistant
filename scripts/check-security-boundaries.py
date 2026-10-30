"""Verify deployed authentication, Redis ACL, DML grants and network separation; no model call."""
import argparse
import json
import subprocess


PROBE = r'''
import os, time, urllib.error, urllib.request
import redis
from app import budget
from app.config import settings

def status(url, headers):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

business = {'X-Agent-Internal-Key': os.environ['AGENT_INTERNAL_API_KEY']}
monitor = {'X-Monitoring-Key': os.environ['MONITORING_API_KEY']}
for base, path in [('http://127.0.0.1:8800', '/metrics'), (os.environ['JAVA_BASE_URL'], '/actuator/prometheus')]:
    assert status(base + path, monitor) == 200, 'Monitor cannot scrape'
    assert status(base + path, business) in (401,403), 'Business key grants monitoring access'
    assert status(base + path, {}) in (401,403), 'Anonymous monitoring access'
assert status('http://127.0.0.1:8800/ready', monitor) == 401, 'Monitoring key grants business access'
client = redis.Redis.from_url(os.environ['REDIS_URL'], username=os.environ['REDIS_USERNAME'], password=os.environ['REDIS_PASSWORD'], decode_responses=True)
def denied(call):
    try:
        call()
    except (redis.exceptions.ResponseError, redis.exceptions.AuthenticationError):
        return
    raise AssertionError('Forbidden Redis operation succeeded')
denied(lambda: redis.Redis(host='redis', socket_timeout=3).ping())
assert client.ping()
key = 'ai-order-agent:security-probe'
try:
    client.set(key, 'ok', ex=30)
    assert client.get(key) == 'ok'
    assert client.eval("return redis.call('GET', KEYS[1])", 1, key) == 'ok'
    denied(lambda: client.get('merchantMenu::1'))
    denied(lambda: client.config_get('*'))
    denied(lambda: client.acl_list())
    denied(lambda: client.flushall())
finally:
    client.delete(key)
settings.rate_limit_key_prefix = 'ai-order-agent:security-probe'
with budget.admission(0, time.time() + 30, campus_user_id=1):
    budget.reserve_tokens(1)
    reservation = budget.current.get()
    quota, receipt = reservation.redis_key, reservation.receipt_key
    assert int(client.get(quota)) == settings.request_token_budget
try:
    assert client.get(quota) == '1' and client.get(receipt) is None, 'Quota settlement failed under ACL'
finally:
    client.delete(quota, receipt)
print('PASS independent monitoring credentials and Agent Redis ACL')
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='ai-order-assistant')
    args = parser.parse_args()
    def run(command, **kwargs):
        return subprocess.run(command, check=True, stdout=subprocess.PIPE, text=True, encoding='utf-8', **kwargs).stdout
    ids = run(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={args.project}']).split()
    containers = json.loads(run(['docker', 'inspect', *ids]))
    services = {c['Config']['Labels']['com.docker.compose.service']: c for c in containers}
    def networks(name):
        return set(services[name]['NetworkSettings']['Networks'])
    assert not (networks('agent') & networks('mysql')), 'Agent can reach database network'
    if 'prometheus' in services:
        assert not (networks('prometheus') & (networks('mysql') | networks('redis')))
    env = dict(v.split('=', 1) for v in services['gateway']['Config']['Env'])
    assert 'DB_MIGRATION_PASSWORD' not in env
    assert env['SPRING_FLYWAY_ENABLED'] == 'false'
    migration = services['migrate']['State']
    assert migration['Status'] == 'exited' and migration['ExitCode'] == 0, 'Migration did not complete'
    print(run(['docker', 'exec', services['agent']['Id'], 'python', '-c', PROBE]).strip())
    denied = run(['docker', 'exec', services['redis']['Id'], 'sh', '-c',
        'REDISCLI_AUTH="$REDIS_GATEWAY_PASSWORD" exec redis-cli --user gateway GET ai-order-agent:security-probe'])
    assert 'NOPERM' in denied, 'Gateway can read Agent keys'
    sql = 'SELECT 1; SHOW GRANTS FOR CURRENT_USER();'
    grants = run(['docker', 'exec', '-i', services['mysql']['Id'], 'sh', '-c',
        'MYSQL_PWD="$DB_APP_PASSWORD" exec mysql -h127.0.0.1 -uai_order_app -N ai_order_assistant'], input=sql).upper()
    assert all(f' {priv}' not in grants for priv in ('ALL PRIVILEGES', 'CREATE', 'ALTER', 'DROP', 'GRANT OPTION', 'FILE', 'PROCESS'))
    assert 'SELECT, INSERT, UPDATE, DELETE ON' in grants, 'Runtime DML grants missing'
    assert r'`AI\_ORDER\_ASSISTANT`' in grants.replace('\\\\', '\\'), 'Database grant contains wildcard underscores'
    print('PASS migration exit, runtime DML-only grants, secret and network separation')


if __name__ == '__main__':
    main()
