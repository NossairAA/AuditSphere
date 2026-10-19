#!/usr/bin/env python3
"""Prepare BFF secrets, then idempotently configure the local OIDC web client."""
from pathlib import Path
import json, os, secrets, sys, time, urllib.request, urllib.parse, urllib.error
root=Path(__file__).resolve().parents[1]
os.umask(0o077)
if not (root/'.env').exists(): raise SystemExit('Exécuter scripts/init-local.py avant ce script.')
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
production='--production' in sys.argv
app_origin=env.get('PROD_APP_ORIGIN','') if production else 'http://localhost:3000'
identity_origin=env.get('PROD_OIDC_ORIGIN','') if production else 'http://localhost:8180'
if production and (not app_origin.startswith('https://') or not identity_origin.startswith('https://')):
 raise SystemExit('PROD_APP_ORIGIN et PROD_OIDC_ORIGIN HTTPS sont obligatoires.')

missing={k:secrets.token_urlsafe(32) for k in ['WEB_DB_PASSWORD','WEB_CLIENT_SECRET','DIRECTORY_CLIENT_SECRET'] if k not in env}
if missing:
 with (root/'.env').open('a') as f:
  for k,v in missing.items(): f.write(f'{k}={v}\n')
 env.update(missing)
web=root/'apps/web/.env.local'
if not web.exists():
 web.write_text('\n'.join(['APP_ORIGIN=http://localhost:3000','OIDC_ISSUER=http://localhost:8180/realms/auditsphere','OIDC_CLIENT_ID=auditsphere-web','OIDC_CLIENT_SECRET='+env['WEB_CLIENT_SECRET'],'SESSION_DATABASE_URL=postgresql://web:'+env['WEB_DB_PASSWORD']+'@localhost:5544/web','API_ORIGIN=http://localhost:8080','ALLOW_INSECURE_LOCAL=true','']) )
if production:
 settings=dict(l.split('=',1) for l in web.read_text().splitlines() if '=' in l)
 settings.update({'APP_ORIGIN':app_origin,'OIDC_ISSUER':identity_origin+'/realms/auditsphere','ALLOW_INSECURE_LOCAL':'false'})
 web.write_text(''.join(k+'='+v+'\n' for k,v in settings.items()))
if '--configure-only' in sys.argv:
 print('Configuration privée du BFF prête.');raise SystemExit(0)
def call(path,data=None,method=None,token=None,form=False):
 headers={}
 if token: headers['Authorization']='Bearer '+token
 if data is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
 payload=(urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
 with urllib.request.urlopen(urllib.request.Request(identity_origin+path,data=payload,headers=headers,method=method),timeout=10) as response:
  body=response.read();return json.loads(body) if body else None
for i in range(90):
 try:
  auth=call('/realms/master/protocol/openid-connect/token',{'client_id':'admin-cli','grant_type':'password','username':'admin','password':env['KEYCLOAK_ADMIN_PASSWORD']},form=True);break
 except (OSError,ValueError):time.sleep(2)
else:raise SystemExit('Keycloak indisponible ou authentification administrateur refusée.')
token=auth['access_token']
# Provision the role in every environment, but demo accounts only in local mode.
base='/admin/realms/auditsphere'
try:
 validator_role=call(base+'/roles/AUDIT_VALIDATOR',token=token)
except urllib.error.HTTPError as error:
 if error.code != 404: raise
 call(base+'/roles',{'name':'AUDIT_VALIDATOR','description':'Validation des missions, workflow à venir'},'POST',token)
 validator_role=call(base+'/roles/AUDIT_VALIDATOR',token=token)
if not production:
 validators=call(base+'/users?username=validator&exact=true',token=token)
 if not validators:
  if 'VALIDATOR_PASSWORD' not in env:
   env['VALIDATOR_PASSWORD']=secrets.token_urlsafe(24)
   with (root/'.env').open('a') as f: f.write('VALIDATOR_PASSWORD='+env['VALIDATOR_PASSWORD']+'\n')
  call(base+'/users',{'username':'validator','enabled':True,'emailVerified':True,'firstName':'Thomas','lastName':'Laurent','email':'validator@example.invalid','credentials':[{'type':'password','value':env['VALIDATOR_PASSWORD'],'temporary':False}]},'POST',token)
  validators=call(base+'/users?username=validator&exact=true',token=token)
 # Never reset a password or add a role to an existing unrelated account.
 user=validators[0]
 if user.get('email') != 'validator@example.invalid':
  raise SystemExit('Le compte validator existe déjà avec une autre identité. Aucune modification de ses droits.')
 call(base+'/users/'+user['id']+'/role-mappings/realm',[validator_role],'POST',token)
print('Rôle AUDIT_VALIDATOR prêt. Aucun accès global aux missions ajouté.')
client={'clientId':'auditsphere-web','name':'AuditSphere Web','protocol':'openid-connect','publicClient':False,'secret':env['WEB_CLIENT_SECRET'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'serviceAccountsEnabled':False,'redirectUris':[app_origin+'/auth/callback'],'webOrigins':[app_origin],'attributes':{'pkce.code.challenge.method':'S256','post.logout.redirect.uris':app_origin+'/'},'protocolMappers':[{'name':'api-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.custom.audience':'auditsphere-api','access.token.claim':'true','id.token.claim':'false'}}]}
existing=call('/admin/realms/auditsphere/clients?clientId=auditsphere-web',token=token)
if existing:call('/admin/realms/auditsphere/clients/'+existing[0]['id'],client,'PUT',token)
else:call('/admin/realms/auditsphere/clients',client,'POST',token)
print('Client web confidentiel configuré avec Authorization Code et PKCE. Aucun secret affiché.')

# Separate read-only account for the Spring directory adapter.
base='/admin/realms/auditsphere'
directory={'clientId':'auditsphere-directory','protocol':'openid-connect','publicClient':False,'secret':env['DIRECTORY_CLIENT_SECRET'],'standardFlowEnabled':False,'directAccessGrantsEnabled':False,'serviceAccountsEnabled':True}
existing=call(base+'/clients?clientId=auditsphere-directory',token=token)
if existing:call(base+'/clients/'+existing[0]['id'],directory,'PUT',token)
else:call(base+'/clients',directory,'POST',token)
client_id=call(base+'/clients?clientId=auditsphere-directory',token=token)[0]['id']
account=call(base+'/clients/'+client_id+'/service-account-user',token=token)
management=call(base+'/clients?clientId=realm-management',token=token)[0]['id']
roles=[call(base+'/clients/'+management+'/roles/'+name,token=token) for name in ['view-users','view-realm']]
call(base+'/users/'+account['id']+'/role-mappings/clients/'+management,roles,'POST',token)
print('Annuaire Spring configuré avec les rôles view-users et view-realm, sans droit de modification.')

for realm in ['auditsphere','master']:
 settings={'bruteForceProtected':True,'permanentLockout':False,'failureFactor':5,'waitIncrementSeconds':30,'minimumQuickLoginWaitSeconds':30,'maxFailureWaitSeconds':900,'maxDeltaTimeSeconds':43200,'eventsEnabled':True,'eventsExpiration':604800,'adminEventsEnabled':True,'adminEventsDetailsEnabled':False}
 if production:settings['sslRequired']='all'
 if realm=='auditsphere':settings.update({'revokeRefreshToken':True,'refreshTokenMaxReuse':0})
 call('/admin/realms/'+realm,settings,'PUT',token)
print('Protection des connexions et rotation des refresh tokens activées.')

if production:
 demo=call('/admin/realms/auditsphere/clients?clientId=auditsphere-demo',token=token)
 if demo:call('/admin/realms/auditsphere/clients/'+demo[0]['id'],{'enabled':False,'directAccessGrantsEnabled':False},'PUT',token)
 print('Profil production : HTTPS obligatoire et client de démonstration désactivé.')
