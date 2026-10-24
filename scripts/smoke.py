#!/usr/bin/env python3
"""Real Keycloak JWTs through gateway; no tokens/passwords printed."""
import json,time,urllib.request,urllib.error,urllib.parse
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
def request(url,token=None,data=None,form=False):
 headers={}
 if token: headers['Authorization']='Bearer '+token
 if data is not None: headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
 payload=(urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(url,data=payload,headers=headers),timeout=10) as response: return response.status,json.loads(response.read() or '{}')
 except urllib.error.HTTPError as e: return e.code,{}
base='http://localhost:8080'
for attempt in range(120):
 try:
  if request(base+'/actuator/health')[0]==200 and request('http://localhost:8180/realms/auditsphere/.well-known/openid-configuration')[0]==200: break
 except (OSError,ValueError): pass
 time.sleep(2)
else: raise SystemExit('Services indisponibles après 4 minutes')
tokens={}
for name in ['manager','auditor','outsider','validator','auditee']:
 status,body=request('http://localhost:8180/realms/auditsphere/protocol/openid-connect/token',data={'client_id':'auditsphere-demo','grant_type':'password','username':name,'password':env[name.upper()+'_PASSWORD']},form=True)
 assert status==200, f'Login {name}: {status}'
 tokens[name]=body['access_token']
payload={'title':'Audit achats démonstration','description':'Données synthétiques','perimeter':'Achats','assigneeSubjects':['00000000-0000-0000-0000-000000000002']}
status,mission=request(base+'/missions',tokens['manager'],payload)
assert status==201, f'Création: {status}'
url=base+'/missions/'+mission['id']
for name,expected in [('manager',200),('auditor',200),('outsider',404),('validator',404)]:
 status,_=request(url,tokens[name]);assert status==expected,(name,status);print(name+': '+str(status))
assert request(base+'/missions',tokens['validator'],payload)[0]==403
assert request(base+'/missions/auditors',tokens['validator'])[0]==403
assert request(url)[0]==401
assert request(url,'invalid-token')[0]==401
assert request(base+'/missions',tokens['auditor'],payload)[0]==403
assert request(base+'/missions',tokens['manager'],{**payload,'title':''})[0]==400
status,actions=request(base+'/remediation/me/actions',tokens['auditee'])
assert status==200 and isinstance(actions,list), f'Actions audité : {status}'
assert request(base+'/missions/'+mission['id'],tokens['auditee'])[0]==404
status,listing=request(base+'/missions',tokens['auditee'])
assert status==200 and listing['total']==0, f'Missions visibles par l’audité : {status}'
assert request(base+'/remediation/me/actions',tokens['auditor'])[0]==403
assert request(base+'/remediation/me/actions')[0]==401
assert request(base+'/remediation/me/actions','invalid-token')[0]==401
print('PASS : création, consultation autorisée, isolation, authentification, rôle et validation.')
print('PASS : remediation-service joignable par la gateway, réservé à l’audité, sans accès aux missions.')
status,body=request('http://localhost:8180/realms/auditsphere/protocol/openid-connect/token',data={'client_id':'remediation-service','client_secret':env['REMEDIATION_SYNC_CLIENT_SECRET'],'grant_type':'client_credentials'},form=True)
assert status==200, f'Jeton de service : {status}'
import base64
part=body['access_token'].split('.')[1]
claims=json.loads(base64.urlsafe_b64decode(part+'='*(-len(part)%4)))
audience=claims['aud'] if isinstance(claims['aud'],list) else [claims['aud']]
assert 'REMEDIATION_SYNC' in claims.get('realm_access',{}).get('roles',[]) and 'auditsphere-api' in audience
assert not {'AUDIT_MANAGER','AUDITOR','AUDIT_VALIDATOR','AUDITEE','SUPER_ADMIN'} & set(claims['realm_access']['roles'])
# The internal snapshot route exists only behind the gateway, which never routes it.
assert request(base+'/internal/missions/'+mission['id']+'/issues/1',body['access_token'])[0]==404
assert request(base+'/internal/missions/'+mission['id']+'/issues/1',tokens['manager'])[0]==404
status,listing=request(base+'/missions',body['access_token'])
assert status==200 and listing['total']==0
print('PASS : compte de service REMEDIATION_SYNC sans rôle humain, lecture interne non exposée par la gateway.')
print('Mission de démonstration : '+mission['id'])
