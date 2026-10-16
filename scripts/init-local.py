#!/usr/bin/env python3
"""Generate private development credentials once; never overwrite an existing realm."""
from pathlib import Path
import json, secrets, os
root=Path(__file__).resolve().parents[1]
if (root/'.env').exists() or (root/'.local/realm.json').exists():
    raise SystemExit('Configuration déjà présente : aucune modification.')
os.umask(0o077)
(root/'.local').mkdir(exist_ok=True)
passwords={k:secrets.token_urlsafe(24) for k in ['AUDIT_DB_PASSWORD','KEYCLOAK_DB_PASSWORD','KEYCLOAK_ADMIN_PASSWORD','MANAGER_PASSWORD','AUDITOR_PASSWORD','OUTSIDER_PASSWORD']}
(root/'.env').write_text(''.join(f'{k}={v}\n' for k,v in passwords.items()))
users=[]
for i,(name,role) in enumerate([('manager','AUDIT_MANAGER'),('auditor','AUDITOR'),('outsider','AUDITOR')],1):
 users.append({'id':f'00000000-0000-0000-0000-{i:012d}','username':name,'enabled':True,'emailVerified':True,'firstName':name,'lastName':'Demo','email':name+'@example.invalid','realmRoles':[role],'credentials':[{'type':'password','value':passwords[name.upper()+'_PASSWORD'],'temporary':False}]})
realm={'realm':'auditsphere','enabled':True,'sslRequired':'none','roles':{'realm':[{'name':'AUDIT_MANAGER'},{'name':'AUDITOR'}]},'users':users,'clients':[{'clientId':'auditsphere-demo','publicClient':True,'standardFlowEnabled':False,'directAccessGrantsEnabled':True,'protocol':'openid-connect','protocolMappers':[{'name':'api-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.custom.audience':'auditsphere-api','access.token.claim':'true','id.token.claim':'false'}}]}]}
(root/'.local/realm.json').write_text(json.dumps(realm,indent=2))
print('Configuration locale privée créée. Aucun mot de passe affiché.')
