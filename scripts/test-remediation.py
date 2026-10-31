#!/usr/bin/env python3
"""End-to-end F-006 check through the gateway, local Compose or k3s (HOST-013).

A synthetic mission is approved with one validated finding. remediation-service must receive the issue by Kafka
and create the follow-up, an action assigned to auditee must reach notification-service, and an attachment must
round-trip through Garage. The attachment is removed at the end. The mission stays until the nightly demo reset.
"""
import base64, hashlib, json, time, urllib.error, urllib.parse, urllib.request, uuid
import target

base = target.API_BASE


def request(path, token=None, body=None, method=None, raw=None, content_type='application/json'):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if data is not None:
        headers['Content-Type'] = content_type
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=20) as response:
            payload = response.read()
            if response.headers.get_content_type() == 'application/json':
                return response.status, json.loads(payload or b'null')
            return response.status, payload
    except urllib.error.HTTPError as error:
        return error.code, None


def call(path, token, body=None, method=None, expected=200):
    status, payload = request(path, token, body, method)
    assert status == expected, (method or ('POST' if body is not None else 'GET'), path.split('?')[0], status)
    return payload


def wait(check, label, seconds=target.WAIT):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(1)
    raise AssertionError('Délai dépassé : ' + label)


def token(name):
    payload = urllib.parse.urlencode({'client_id': 'auditsphere-demo', 'grant_type': 'password', 'username': name,
                                      'password': target.secret(name.upper() + '_PASSWORD')}).encode()
    with urllib.request.urlopen(target.TOKEN_URL, data=payload, timeout=10) as response:
        return json.load(response)['access_token']


def subject(jwt):
    claims = jwt.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(claims + '=' * (-len(claims) % 4)))['sub']


tokens = {name: token(name) for name in ['manager', 'auditor', 'validator', 'auditee']}
subjects = {name: subject(jwt) for name, jwt in tokens.items()}

# 1. Approved mission with one validated finding: audit-service emits audit.mission.findings-issued.v1.
mission = call('/missions', tokens['manager'], {
    'title': 'Test F-006 ' + str(uuid.uuid4())[:8], 'description': 'Données synthétiques de validation des actions correctives',
    'perimeter': 'Tests ' + ('k3s' if target.K3S else 'locaux'), 'assigneeSubjects': [subjects['auditor']],
    'plannedStartDate': time.strftime('%Y-%m-%d', time.gmtime()), 'plannedEndDate': time.strftime('%Y-%m-%d', time.gmtime(time.time() + 7 * 86400))},
    expected=201)
mid = mission['id']
program = call(f'/missions/{mid}/risks', tokens['manager'], {
    'version': mission['version'], 'title': 'Risque synthétique', 'description': 'Risque de test',
    'probability': 3, 'impact': 3}, 'PUT')
program = call(f'/missions/{mid}/controls', tokens['manager'], {
    'version': program['mission']['version'], 'objective': 'Contrôle synthétique', 'procedure': 'Procédure de test',
    'riskIds': [program['risks'][0]['id']]}, 'PUT')
control = program['controls'][0]['id']
mission = call(f'/missions/{mid}/status', tokens['manager'], {'version': program['mission']['version'], 'status': 'IN_PROGRESS'}, 'PUT')
call(f'/missions/{mid}/results', tokens['auditor'], {
    'version': mission['version'], 'controlId': control, 'outcome': 'NON_CONFORMING', 'comment': 'Écart de test'}, 'PUT')
finding = call(f'/missions/{mid}/findings', tokens['auditor'], {
    'title': 'Constat synthétique', 'condition': 'Constat de test', 'criterion': 'Critère de test', 'cause': 'Cause de test',
    'consequence': 'Conséquence de test', 'recommendation': 'Recommandation de test', 'severity': 'HIGH',
    'controlIds': [control]}, expected=201)
path = f'/missions/{mid}/findings/{finding["id"]}/transition'
finding = call(path, tokens['auditor'], {'version': finding['version'], 'action': 'SUBMIT'}, 'PUT')
call(path, tokens['manager'], {'version': finding['version'], 'action': 'VALIDATE'}, 'PUT')
mission = call(f'/missions/{mid}', tokens['manager'])
mission = call(f'/missions/{mid}/validator', tokens['manager'], {'version': mission['version'], 'validatorSubject': subjects['validator']}, 'PUT')
mission = call(f'/missions/{mid}/submission', tokens['manager'], {'version': mission['version']}, 'PUT')
mission = call(f'/missions/{mid}/review', tokens['validator'], {
    'version': mission['version'], 'decision': 'APPROVED', 'comment': 'Approbation de test'}, 'PUT')
assert mission['status'] == 'APPROVED', mission['status']
print('PASS : mission ' + mid + ' approuvée avec un constat validé.', flush=True)


# 2. The follow-up appears in remediation-service, synchronised through Kafka.
def follow_ups():
    status, body = request(f'/remediation/missions/{mid}/follow-ups', tokens['manager'])
    return body if status == 200 and body['followUps'] else None


scope = wait(follow_ups, 'suivi créé par la synchronisation')
assert scope['issueCycle'] == 1 and len(scope['followUps']) == 1, scope['issueCycle']
follow_up = scope['followUps'][0]
assert follow_up['status'] == 'TO_PLAN' and follow_up['title'] == 'Constat synthétique', follow_up['status']
print('PASS : suivi À planifier créé par Kafka pour le constat émis.', flush=True)

# 3. Action assigned to auditee: the outbox publishes audit.action.assigned.v1, notification-service notifies.
found = call('/remediation/directory/auditees?q=auditee', tokens['manager'])
assert any(p.get('subject') == subjects['auditee'] for p in found), 'auditee absent de l’annuaire'
due = time.strftime('%Y-%m-%d', time.gmtime(time.time() + 30 * 86400))
action = call(f'/remediation/follow-ups/{follow_up["id"]}/actions', tokens['manager'], {
    'description': 'Action synthétique', 'ownerSubject': subjects['auditee'], 'dueDate': due}, expected=201)


def notified(name):
    status, body = request('/notifications?size=100', tokens[name])
    assert status == 200, status
    return any(n.get('actionId') == action['id'] for n in body['items'])


wait(lambda: notified('auditee'), 'notification de l’action')
assert not notified('manager') and not notified('auditor')
print('PASS : affectation publiée par l’outbox et notifiée au seul audité.', flush=True)

# 4. Attachment round trip through the remediation bucket, then removed by its author.
pdf = b'%PDF-1.4\n% Justificatif synthetique ' + uuid.uuid4().hex.encode() + b'\n%%EOF\n'
boundary = uuid.uuid4().hex
form = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="justificatif.pdf"\r\n'
        f'Content-Type: application/pdf\r\n\r\n').encode() + pdf + f'\r\n--{boundary}--\r\n'.encode()
detail = call(f'/remediation/actions/{action["id"]}', tokens['auditee'])
status, attachment = request(f'/remediation/actions/{action["id"]}/attachments?version={detail["action"]["version"]}', tokens['auditee'],
                             raw=form, method='POST', content_type='multipart/form-data; boundary=' + boundary)
assert status == 201, ('dépôt', status)
assert attachment['sha256'] == hashlib.sha256(pdf).hexdigest()
status, content = request(f'/remediation/actions/{action["id"]}/attachments/{attachment["id"]}', tokens['auditor'])
assert status == 200 and content == pdf, ('téléchargement', status)
detail = call(f'/remediation/actions/{action["id"]}', tokens['auditee'])
status, _ = request(f'/remediation/actions/{action["id"]}/attachments/{attachment["id"]}?version={detail["action"]["version"]}',
                    tokens['auditee'], method='DELETE')
assert status == 204, ('retrait', status)
status, _ = request(f'/remediation/actions/{action["id"]}/attachments/{attachment["id"]}', tokens['auditor'])
assert status == 404, ('après retrait', status)
print('PASS : justificatif déposé, relu identique par l’auditeur, puis retiré.', flush=True)
print('PASS : actions correctives de bout en bout validées.', flush=True)
