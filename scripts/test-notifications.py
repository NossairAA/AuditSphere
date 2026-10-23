#!/usr/bin/env python3
"""Real Kafka/Keycloak checks. --faults briefly stops local services, always restarted."""
import argparse, json, subprocess, time, urllib.request, urllib.error, urllib.parse, uuid
from pathlib import Path
root = Path(__file__).resolve().parents[1]
env = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)
parser = argparse.ArgumentParser()
parser.add_argument('--faults', action='store_true')
args = parser.parse_args()
base = 'http://localhost:8080'
recipient = '00000000-0000-0000-0000-000000000002'

def compose(*command):
    subprocess.run(['docker', 'compose', *command], cwd=root, check=True, stdout=subprocess.DEVNULL)

def sql(db, statement):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', db+'-db', 'psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-U', db+'_admin', '-d', db, '-c', statement], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()

def request(path, token=None, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer '+token
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(base+path, data=data, headers=headers, method=method), timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, {}

def wait(check, label, seconds=120):
    deadline = time.monotonic()+seconds
    while time.monotonic() < deadline:
        try:
            if check(): return
        except (OSError, ValueError, subprocess.CalledProcessError): pass
        time.sleep(1)
    raise AssertionError('Timeout: '+label)

def token(name):
    payload = urllib.parse.urlencode({'client_id':'auditsphere-demo', 'grant_type':'password', 'username':name, 'password':env[name.upper()+'_PASSWORD']}).encode()
    with urllib.request.urlopen('http://localhost:8180/realms/auditsphere/protocol/openid-connect/token', data=payload, timeout=10) as response:
        return json.load(response)['access_token']

wait(lambda: request('/notifications', token('auditor'))[0] == 200, 'notification API')
tokens = {name: token(name) for name in ['manager', 'auditor', 'outsider']}

def create(label):
    status, body = request('/missions', tokens['manager'], {'title':'Test notifications '+label+' '+str(uuid.uuid4())[:8], 'description':'Données synthétiques de validation Kafka', 'perimeter':'Tests locaux', 'assigneeSubjects':[recipient]})
    assert status == 201, ('create', status)
    return str(uuid.UUID(body['id']))

def count(mission):
    return int(sql('notification', "SELECT count(*) FROM notifications WHERE mission_id='"+mission+"'"))

def published(mission):
    return sql('audit', "SELECT count(*) FROM assignment_outbox WHERE mission_id='"+mission+"' AND published_at IS NULL") == '0'

def delivered(mission, expected=1):
    wait(lambda: count(mission) == expected, 'delivery '+mission)
    wait(lambda: published(mission), 'publication acknowledged')

def visible(mission, name):
    status, body = request('/notifications?size=100', tokens[name])
    assert status == 200, status
    return any(n['missionId'] == mission for n in body['items'])

mission = create('nominal')
delivered(mission)
assert visible(mission, 'auditor')
assert not visible(mission, 'manager') and not visible(mission, 'outsider')
assert request('/notifications')[0] == 401
assert request('/notifications', 'invalid-token')[0] == 401
print('PASS : livraison via Kafka et isolation API avec vrais JWT.', flush=True)
# Simulate a broker acknowledgment followed by an outbox-marking crash.
# Only this synthetic mission is replayed, never the user's existing events.
sql('audit', "UPDATE assignment_outbox SET published_at=NULL, available_at=now(), lease_until=NULL, lease_token=NULL WHERE mission_id='"+mission+"'")
wait(lambda: published(mission), 'duplicate published')
# A second genuine assignment is a barrier on the same Kafka partition.
for version, subjects in [(0, []), (1, [recipient])]:
    status, _ = request('/missions/'+mission+'/assignments', tokens['manager'], {'version':version, 'assigneeSubjects':subjects}, 'PUT')
    assert status == 200, status

delivered(mission, 2)
assert count(mission) == 2
print('PASS : événement rejoué sans doublon, nouvelle affectation distincte conservée.', flush=True)

if args.faults:
    # Same key routes both records to the same partition. A valid barrier must pass
    # only after the poison record has been acknowledged in its DLT.
    poison_mission = str(uuid.uuid4())
    poison_event = str(uuid.uuid4())
    poison_key = 'security-test-' + poison_mission
    barrier = {'eventId': poison_event, 'eventType': 'audit.mission.assigned.v1',
               'eventVersion': 1, 'missionId': poison_mission,
               'recipientSubject': recipient, 'aggregateVersion': 0,
               'occurredAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    records = poison_key + ':invalid-json\n' + poison_key + ':' + json.dumps(barrier) + '\n'
    subprocess.run(['docker', 'compose', 'exec', '-T', 'kafka',
                    '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka:9092',
                    '--topic', 'audit.mission.assigned.v1', '--property', 'parse.key=true',
                    '--property', 'key.separator=:'], input=records, text=True,
                   cwd=root, check=True, stdout=subprocess.DEVNULL, timeout=30)
    try:
        wait(lambda: count(poison_mission) == 1, 'valid record after poison record')
        quarantined = subprocess.run(['docker', 'compose', 'exec', '-T', 'kafka',
                        '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'kafka:9092',
                        '--topic', 'audit.mission.assigned.v1.DLT', '--from-beginning',
                        '--timeout-ms', '10000', '--property', 'print.key=true'],
                       cwd=root, text=True, capture_output=True, timeout=20)
        assert poison_key + '\tinvalid-json' in quarantined.stdout, 'Poison record not confirmed in DLT'
        print('PASS : message invalide en DLT, message suivant livré.', flush=True)
    finally:
        sql('notification', "DELETE FROM notifications WHERE event_id='" + poison_event + "'")
    for service in ['notification-service', 'kafka', 'notification-db']:
        try:
            compose('stop', service)
            start = time.monotonic()
            mission = create('panne '+service)
            assert time.monotonic()-start < 10, 'Business request blocked by downstream outage'
            if service == 'kafka':
                time.sleep(4)
                assert not published(mission), 'Outbox must remain pending while Kafka is down'
                assert count(mission) == 0
            else:
                wait(lambda: published(mission), 'event published while consumer is unavailable')
                if service == 'notification-service': assert count(mission) == 0
        finally:
            compose('start', service)
        delivered(mission)
        print('PASS : reprise après arrêt de '+service+', notification unique.', flush=True)
print('PASS : notifications et résilience validées.', flush=True)
