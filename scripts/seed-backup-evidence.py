#!/usr/bin/env python3
"""Add two synthetic evidence files to one in-progress demo mission, so the restore test checks real hashes.

Run through scripts/k3s-check.sh seed-backup-evidence.py. Idempotent: files already present are skipped.
No token or password is printed.
"""
import base64
import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid

import target

PREFIX = 'temoin-sauvegarde-'
FILES = [
    (PREFIX + 'releve.pdf', 'application/pdf',
     b'%PDF-1.4\n% AuditSphere: synthetic evidence for the backup restore test.\n%%EOF\n'),
    # 1x1 transparent PNG.
    (PREFIX + 'capture.png', 'image/png', base64.b64decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=')),
]


def call(url, token=None, data=None, headers=None, form=False):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if form:
        data = urllib.parse.urlencode(data).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=20) as response:
            return response.status, json.loads(response.read() or b'{}')
    except urllib.error.HTTPError as error:
        return error.code, {}


status, body = call(target.TOKEN_URL, data={'client_id': 'auditsphere-demo', 'grant_type': 'password',
                                           'username': 'auditor', 'password': target.secret('AUDITOR_PASSWORD')}, form=True)
assert status == 200, f'Connexion auditor : {status}'
token = body['access_token']
part = token.split('.')[1]
subject = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))['sub']

existing = set(target.sql('audit', f"SELECT original_name FROM control_evidence WHERE original_name LIKE '{PREFIX}%'").split())
missing = [f for f in FILES if f[0] not in existing]
if not missing:
    raise SystemExit('Preuves témoins déjà présentes, aucun ajout.')

row = target.sql('audit', f"""SELECT m.id, c.id FROM missions m
 JOIN mission_controls c ON c.mission_id = m.id
 JOIN mission_assignments a ON a.mission_id = m.id AND a.subject = '{subject}'
 WHERE m.status = 'IN_PROGRESS' AND m.deleted_at IS NULL ORDER BY m.created_at, c.id LIMIT 1""")
assert row, 'Aucune mission en cours affectée à auditor avec un contrôle.'
mission, control = row.split('|')
for name, media_type, content in missing:
    version = target.sql('audit', f"SELECT version FROM missions WHERE id = '{mission}'")
    boundary = uuid.uuid4().hex
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
               f'Content-Type: {media_type}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    url = f'{target.API_BASE}/missions/{mission}/controls/{control}/evidence?version={version}'
    status, evidence = call(url, token, payload, {'Content-Type': 'multipart/form-data; boundary=' + boundary})
    assert status == 201, f'Dépôt {name} : {status}'
    assert evidence['sha256'] == hashlib.sha256(content).hexdigest(), f'Empreinte inattendue pour {name}'
    print(f'{name} : déposée, {len(content)} octets.')
print('Preuves témoins prêtes pour l’exercice de restauration.')
