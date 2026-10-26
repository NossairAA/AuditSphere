"""Stack targeted by the check scripts: local Compose by default, k3s when AUDITSPHERE_TARGET=k3s.

The k3s values come from scripts/k3s-check.sh, which opens the gateway port-forward and the Temporal CLI pod.
"""
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
K3S = os.environ.get('AUDITSPHERE_TARGET') == 'k3s'
API_BASE = os.environ.get('AUDITSPHERE_API_BASE', 'http://localhost:8080')
OIDC_BASE = os.environ.get('AUDITSPHERE_OIDC_BASE', 'http://localhost:8180')
TOKEN_URL = OIDC_BASE + '/realms/auditsphere/protocol/openid-connect/token'
# Spring services restart slower on the single k3s node than in Compose.
WAIT = int(os.environ.get('AUDITSPHERE_WAIT', '120'))
_accounts = Path(os.environ.get('AUDITSPHERE_ACCOUNTS_FILE', ROOT / '.env'))
_env = dict(line.split('=', 1) for line in _accounts.read_text().splitlines() if '=' in line)
# PostgreSQL is a single instance in k3s: one database cannot be stopped alone.
WORKLOADS = {'kafka': ('data', 'statefulset/kafka'), 'temporal': ('data', 'deployment/temporal')}


def secret(name):
    """Account passwords and client secrets. The process environment wins over the accounts file."""
    return os.environ.get(name) or _env[name]


def _run(command, input=None, check=True):
    return subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=check, input=input)


def _compose(*command, input=None, check=True):
    return _run(['docker', 'compose', *command], input, check)


def _kubectl(*command, input=None, check=True):
    return _run(['kubectl', *command], input, check)


def sql(db, query):
    if K3S:
        command = ['--namespace', 'data', 'exec', '-i', 'postgres-0', '--', 'psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', db, '-c', query]
        return _kubectl(*command).stdout.strip()
    return _compose('exec', '-T', db + '-db', 'psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-U', db + '_admin', '-d', db, '-c', query).stdout.strip()


def dump(db, output):
    """Custom-format pg_dump of one database into a binary file object."""
    if K3S:
        command = ['kubectl', '--namespace', 'data', 'exec', 'postgres-0', '--', 'pg_dump', '-U', 'postgres', '-d', db, '-Fc']
    else:
        command = ['docker', 'compose', 'exec', '-T', db + '-db', 'pg_dump', '-U', db + '_admin', '-d', db, '-Fc']
    subprocess.run(command, cwd=ROOT, stdout=output, check=True)


def kafka(*command, input=None, check=True):
    """Kafka CLI tool run inside the broker, for example kafka-console-consumer.sh."""
    tool = '/opt/kafka/bin/' + command[0]
    if K3S:
        return _kubectl('--namespace', 'data', 'exec', '-i', 'kafka-0', '--', tool, *command[1:], input=input, check=check)
    return _compose('exec', '-T', 'kafka', tool, *command[1:], input=input, check=check)


def temporal(*command, input=None, check=True):
    if K3S:
        return _kubectl('--namespace', 'data', 'exec', '-i', 'temporal-cli', '--', 'temporal', *command, input=input, check=check)
    return _compose('exec', '-T', 'temporal', 'temporal', *command, input=input, check=check)


def _workload(service):
    if service.endswith('-db'):
        raise ValueError(service + ' : PostgreSQL est partagé dans k3s, une base ne peut pas être arrêtée seule')
    return WORKLOADS.get(service, ('auditsphere', 'deployment/' + service))


def _scale(service, replicas):
    namespace, workload = _workload(service)
    _kubectl('--namespace', namespace, 'scale', workload, '--replicas=' + str(replicas))
    return namespace, workload


def stop(*services):
    if not K3S:
        _compose('stop', *services)
        return
    for service in services:
        namespace, workload = _scale(service, 0)
        selector = _kubectl('--namespace', namespace, 'get', workload, '-o', 'jsonpath={.spec.selector.matchLabels}').stdout
        labels = ','.join(k + '=' + v for k, v in json.loads(selector).items())
        # Completed migration Job pods share the labels: only pods still running count.
        _kubectl('--namespace', namespace, 'wait', '--for=delete', 'pod', '--selector', labels,
                 '--field-selector', 'status.phase!=Succeeded,status.phase!=Failed', '--timeout=180s')


def start(*services):
    """Start and wait until ready. In k3s the rollout waits for the readiness probe."""
    if not K3S:
        _compose('start', *services)
        return
    for service in services:
        namespace, workload = _scale(service, 1)
        _kubectl('--namespace', namespace, 'rollout', 'status', workload, '--timeout=' + str(WAIT) + 's')


def restart(service):
    # A k3s rollout restart would start the new pod before stopping the old one: stop then start instead.
    if K3S:
        stop(service)
        start(service)
    else:
        _compose('restart', service)


def running(services):
    if K3S:
        result = []
        for service in services:
            namespace, workload = _workload(service)
            if _kubectl('--namespace', namespace, 'get', workload, '-o', 'jsonpath={.spec.replicas}').stdout.strip() not in ('', '0'):
                result.append(service)
        return result
    names = set(_compose('ps', '--services', '--status', 'running').stdout.split())
    return [s for s in services if s in names]
