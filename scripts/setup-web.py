#!/usr/bin/env python3
"""Prepare BFF secrets, then idempotently configure the local OIDC web client."""
from pathlib import Path
import json, os, secrets, sys, time, urllib.request, urllib.parse, urllib.error
root=Path(__file__).resolve().parents[1]
os.umask(0o077)
if not (root/'.env').exists(): raise SystemExit('Exécuter scripts/init-local.py avant ce script.')
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
missing={k:secrets.token_urlsafe(32) for k in ['WEB_DB_PASSWORD','WEB_CLIENT_SECRET'] if k not in env}
if missing:
 with (root/'.env').open('a') as f:
  for k,v in missing.items(): f.write(f'{k}={v}\n')
 env.update(missing)
web=root/'apps/web/.env.local'
if not web.exists():
 web.write_text('\n'.join(['APP_ORIGIN=http://localhost:3000','OIDC_ISSUER=http://localhost:8180/realms/auditsphere','OIDC_CLIENT_ID=auditsphere-web','OIDC_CLIENT_SECRET='+env['WEB_CLIENT_SECRET'],'SESSION_DATABASE_URL=postgresql://web:'+env['WEB_DB_PASSWORD']+'@localhost:5544/web','API_ORIGIN=http://localhost:8080','ALLOW_INSECURE_LOCAL=true','']) )
if '--configure-only' in sys.argv:
 print('Configuration privée du BFF prête.');raise SystemExit(0)
def call(path,data=None,method=None,token=None,form=False):
 headers={}
 if token: headers['Authorization']='Bearer '+token
 if data is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
 payload=(urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
 with urllib.request.urlopen(urllib.request.Request('http://localhost:8180'+path,data=payload,headers=headers,method=method),timeout=10) as response:
  body=response.read();return json.loads(body) if body else None
for i in range(90):
 try:
  auth=call('/realms/master/protocol/openid-connect/token',{'client_id':'admin-cli','grant_type':'password','username':'admin','password':env['KEYCLOAK_ADMIN_PASSWORD']},form=True);break
 except (OSError,ValueError):time.sleep(2)
else:raise SystemExit('Keycloak indisponible ou authentification administrateur refusée.')
token=auth['access_token']
client={'clientId':'auditsphere-web','name':'AuditSphere Web','protocol':'openid-connect','publicClient':False,'secret':env['WEB_CLIENT_SECRET'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'serviceAccountsEnabled':False,'redirectUris':['http://localhost:3000/auth/callback'],'webOrigins':['http://localhost:3000'],'attributes':{'pkce.code.challenge.method':'S256','post.logout.redirect.uris':'http://localhost:3000/'},'protocolMappers':[{'name':'api-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.custom.audience':'auditsphere-api','access.token.claim':'true','id.token.claim':'false'}}]}
existing=call('/admin/realms/auditsphere/clients?clientId=auditsphere-web',token=token)
if existing:call('/admin/realms/auditsphere/clients/'+existing[0]['id'],client,'PUT',token)
else:call('/admin/realms/auditsphere/clients',client,'POST',token)
print('Client web confidentiel configuré avec Authorization Code et PKCE. Aucun secret affiché.')
