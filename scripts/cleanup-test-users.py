#!/usr/bin/env python3
"""Delete only explicitly recorded synthetic UX accounts, never ordinary users."""
import json, urllib.request, urllib.parse
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=root/'.local/test-users.json'
if not manifest.exists(): raise SystemExit(0)
env=dict(l.split('=',1) for l in (root/'.env').read_text().splitlines() if '=' in l)
base='http://localhost:8180'
form=urllib.parse.urlencode({'client_id':'admin-cli','grant_type':'password','username':'admin','password':env['KEYCLOAK_ADMIN_PASSWORD']}).encode()
with urllib.request.urlopen(urllib.request.Request(base+'/realms/master/protocol/openid-connect/token',data=form),timeout=10) as r: token=json.load(r)['access_token']
items=json.loads(manifest.read_text())
for item in items:
 if not item['username'].startswith('ux-test-'): raise SystemExit('Compte hors périmètre de test')
 path=base+'/admin/realms/auditsphere/users/'+item['id']
 try:
  with urllib.request.urlopen(urllib.request.Request(path,headers={'Authorization':'Bearer '+token}),timeout=10) as r: current=json.load(r)
 except urllib.error.HTTPError as e:
  if e.code==404: continue
  raise
 if current['username']!=item['username']:raise SystemExit('Identité du compte différente')
 with urllib.request.urlopen(urllib.request.Request(path,method='DELETE',headers={'Authorization':'Bearer '+token}),timeout=10):pass
manifest.unlink()
print('Comptes synthétiques enregistrés nettoyés.')
