"""Verify effective Agent confinement and read-only HTTP dependencies; no model call."""
import argparse
import json
import subprocess


PROBE = r'''
import json, os, pathlib, subprocess, urllib.request
status = dict(line.split(':', 1) for line in pathlib.Path('/proc/1/status').read_text().splitlines() if ':' in line)
assert os.getuid() == 10001 and int(status['Uid'].split()[0]) == 10001
for field in ('CapInh', 'CapPrm', 'CapEff', 'CapBnd', 'CapAmb'):
    assert int(status[field].strip(), 16) == 0, field
assert status['NoNewPrivs'].strip() == '1'
assert status['Seccomp'].strip() == '2'
try:
    subprocess.run(['/usr/bin/infocmp', '-V'], check=True, capture_output=True)
except PermissionError:
    pass
else:
    raise AssertionError('Unused vulnerable infocmp CLI is still executable')
try:
    pathlib.Path('/app/.security-probe').touch()
except OSError as error:
    assert error.errno in (13, 30), error.errno
else:
    raise AssertionError('Application root is writable')
headers = {'X-Agent-Internal-Key': os.environ['AGENT_INTERNAL_API_KEY']}
for path in ('/ready', '/metrics'):
    request = urllib.request.Request('http://127.0.0.1:8800' + path, headers=headers)
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200
        assert response.read()
with urllib.request.urlopen(os.environ['JAVA_BASE_URL'] + '/actuator/health/readiness', timeout=5) as response:
    assert response.status == 200
    assert json.load(response)['status'] == 'UP'
print(json.dumps({'uid': os.getuid(), 'capabilitiesZero': True, 'noNewPrivileges': True,
                  'seccomp': True, 'infocmpBlocked': True, 'applicationRootReadOnly': True,
                  'agentReady': True, 'metrics': True, 'gatewayReachable': True}))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='ai-order-assistant')
    args = parser.parse_args()
    result = subprocess.run(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={args.project}',
                             '--filter', 'label=com.docker.compose.service=agent'], check=True, capture_output=True, text=True)
    containers = result.stdout.split()
    assert len(containers) == 1, 'Expected exactly one running Agent'
    container = containers[0]
    inspected = json.loads(subprocess.run(['docker', 'inspect', container], check=True, capture_output=True, text=True).stdout)[0]
    config = inspected['HostConfig']
    assert config['ReadonlyRootfs'] is True
    assert config['CapDrop'] == ['ALL']
    assert 'no-new-privileges:true' in config['SecurityOpt']
    assert not config['Privileged'] and not config.get('CapAdd')
    assert config['PidMode'] != 'host' and config['NetworkMode'] != 'host'
    assert not any(m['Destination'] in ('/var/run/docker.sock', '/run/docker.sock') for m in inspected['Mounts'])
    probe = subprocess.run(['docker', 'exec', container, 'python', '-c', PROBE], check=True, capture_output=True, text=True)
    print(json.dumps({'project': args.project, 'image': inspected['Image'], **json.loads(probe.stdout)}, indent=2))


if __name__ == '__main__':
    main()
