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
for name in ['manager','auditor','outsider','validator']:
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
print('PASS : création, consultation autorisée, isolation, authentification, rôle et validation.')
print('Mission de démonstration : '+mission['id'])
