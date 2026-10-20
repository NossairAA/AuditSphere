#!/usr/bin/env python3
"""Verify privileged overrides with real tokens and actual Temporal completion."""
import base64, json, subprocess, time, urllib.request, urllib.parse, uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=dict(l.split('=',1) for l in (root/'.env').read_text().splitlines() if '=' in l)
def token(user):
 data=urllib.parse.urlencode({'client_id':'auditsphere-demo','grant_type':'password','username':user,'password':env[user.upper()+'_PASSWORD']}).encode()
 with urllib.request.urlopen('http://localhost:8180/realms/auditsphere/protocol/openid-connect/token',data=data,timeout=10) as r:return json.load(r)['access_token']
def request(path,bearer,body=None,method='GET'):
 with urllib.request.urlopen(urllib.request.Request('http://localhost:8080'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+bearer,'Content-Type':'application/json'},method=method),timeout=10) as r:
  return json.load(r) if r.status!=204 else None
def sql(query):
 return subprocess.check_output(['docker','compose','exec','-T','audit-db','psql','-X','-At','-U','audit_admin','-d','audit','-c',query],cwd=root,text=True).strip()
def wait_result(cycle,expected):
 for _ in range(60):
  r=subprocess.run(['docker','compose','exec','-T','temporal','temporal','workflow','show','--workflow-id','mission-review-'+cycle,'--output','json'],cwd=root,capture_output=True,text=True)
  if r.returncode==0:
   ends=[e for e in json.loads(r.stdout)['events'] if e['eventType']=='EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED']
   if ends:
    value=ends[0]['workflowExecutionCompletedEventAttributes']['result']['payloads'][0]['data']
    assert json.loads(base64.b64decode(value))==expected
    return
  time.sleep(1)
 raise AssertionError('Temporal did not complete the administrative decision')
manager,admin,validator=token('manager'),token('superadmin'),token('validator')
subject=json.loads(base64.urlsafe_b64decode(validator.split('.')[1]+'=='))['sub']
manifest=root/'.local'/('admin-tests-'+str(uuid.uuid4())+'.txt')
manifest.parent.mkdir(exist_ok=True)
manifest.touch(mode=0o600)
try:
 for action,result in [('APPROVE','APPROVED'),('CLOSE','WITHDRAWN'),('DELETE','WITHDRAWN')]:
  m=request('/missions',manager,{'title':'Administration synthétique '+action,'description':'Validation technique temporaire','perimeter':'Tests','assigneeSubjects':[],'plannedStartDate':'2026-11-01','plannedEndDate':'2026-11-02'},'POST')
  mid=str(uuid.UUID(m['id']))
  with manifest.open('a') as f:f.write(mid+'\n')
  for path,body in [('validator',{'validatorSubject':subject}),('status',{'status':'IN_PROGRESS'}),('submission',{})]:
   m=request('/missions/'+mid+'/'+path,manager,{'version':m['version'],**body},'PUT')
  cycle=sql("SELECT review_cycle_id FROM missions WHERE id='"+mid+"'")
  request('/missions/'+mid+'/override',admin,{'version':m['version'],'action':action,'reason':'Vérification synthétique de la dérogation'},'PUT')
  wait_result(cycle,result)
  entries=request('/missions/'+mid+'/action-log',admin)
  assert any(e['action']=='ADMIN_OVERRIDE' and e['operation']==action and e['previous_status']=='PENDING_REVIEW' for e in entries)
  print('PASS : '+action+', journal et résultat Temporal '+result,flush=True)
finally:
 subprocess.run(['python3',str(root/'scripts/cleanup-test-data.py'),str(manifest)],cwd=root,check=True)
