#!/usr/bin/env python3
"""Verify durable review delivery with real JWTs. --faults stops and restores local services."""
import argparse, base64, json, subprocess, time, urllib.request, urllib.error, urllib.parse, uuid
from pathlib import Path
root = Path(__file__).resolve().parents[1]
env = dict(line.split('=', 1) for line in (root/'.env').read_text().splitlines() if '=' in line)
parser = argparse.ArgumentParser()
parser.add_argument('--faults', action='store_true')
args = parser.parse_args()
base = 'http://localhost:8080'

def compose(*command):
    subprocess.run(['docker','compose',*command],cwd=root,check=True,stdout=subprocess.DEVNULL)

def sql(query):
    return subprocess.check_output(['docker','compose','exec','-T','audit-db','psql','-X','-At','-v','ON_ERROR_STOP=1','-U','audit_admin','-d','audit','-c',query],cwd=root,text=True).strip()

def token(name):
    body=urllib.parse.urlencode({'client_id':'auditsphere-demo','grant_type':'password','username':name,'password':env[name.upper()+'_PASSWORD']}).encode()
    with urllib.request.urlopen('http://localhost:8180/realms/auditsphere/protocol/openid-connect/token',data=body,timeout=10) as r:
        return json.load(r)['access_token']

def request(path, bearer=None, body=None, method=None):
    headers={'Content-Type':'application/json'}
    if bearer: headers['Authorization']='Bearer '+bearer
    try:
        with urllib.request.urlopen(urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method),timeout=10) as r:
            return r.status,json.load(r)
    except urllib.error.HTTPError as e: return e.code,{}

def wait(check, label, seconds=120):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            if check(): return
        except (OSError,ValueError,subprocess.CalledProcessError): pass
        time.sleep(1)
    raise AssertionError('Timeout: '+label)

def describe(cycle):
    out=subprocess.check_output(['docker','compose','exec','-T','temporal','temporal','workflow','describe','--workflow-id','mission-review-'+cycle,'--output','json'],cwd=root,stderr=subprocess.DEVNULL,text=True)
    return json.loads(out)

def info(cycle): return describe(cycle)['workflowExecutionInfo']

def completed(cycle): return info(cycle)['status']=='WORKFLOW_EXECUTION_STATUS_COMPLETED'

def result(cycle):
    raw=subprocess.check_output(['docker','compose','exec','-T','temporal','temporal','workflow','show','--workflow-id','mission-review-'+cycle,'--output','json'],cwd=root,text=True,stderr=subprocess.DEVNULL)
    events=json.loads(raw)['events']
    end=next(e for e in events if e['eventType']=='EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED')
    payload=end['workflowExecutionCompletedEventAttributes']['result']['payloads'][0]['data']
    return json.loads(base64.b64decode(payload))

wait(lambda:request('/actuator/health')[0]==200,'API')
manager,validator=token('manager'),token('validator')
wait(lambda:request('/missions',manager)[0]==200,'mission API')
claims=json.loads(base64.urlsafe_b64decode(validator.split('.')[1]+'=='))
status,mission=request('/missions',manager,{'title':'Temporal '+str(uuid.uuid4())[:8],'description':'Données synthétiques de reprise','perimeter':'Tests','assigneeSubjects':[],'plannedStartDate':'2026-11-01','plannedEndDate':'2026-11-02'})
assert status==201
mid=str(uuid.UUID(mission['id']))

def mutate(resource, body, bearer=manager):
    global mission
    status,updated=request('/missions/'+mid+'/'+resource,bearer,{'version':mission['version'],**body},'PUT')
    assert status==200,(resource,status)
    mission=updated

mutate('validator',{'validatorSubject':claims['sub']})
mutate('status',{'status':'IN_PROGRESS'})
stopped=set()
try:
    mutate('submission',{})
    cycle1=sql("SELECT review_cycle_id FROM missions WHERE id='"+mid+"'")
    wait(lambda:info(cycle1)['status']=='WORKFLOW_EXECUTION_STATUS_RUNNING','workflow started')
    wait(lambda:len([n for n in request('/notifications',validator)[1].get('items',[]) if n['missionId']==mid and n['eventType']=='audit.mission.review-requested.v1'])==1,'validator notification')
    sql("UPDATE assignment_outbox SET published_at=NULL,available_at=now(),lease_until=NULL,lease_token=NULL WHERE mission_id='"+mid+"' AND event_type='audit.mission.review-requested.v1'")
    if args.faults:
        compose('stop','workflow-worker');stopped.add('workflow-worker')
    mutate('review',{'decision':'REJECTED','comment':'Correction synthétique'},validator)
    wait(lambda:sql("SELECT count(*) FROM review_workflow_outbox WHERE cycle_id='"+cycle1+"' AND published_at IS NULL")=='0','decision accepted by Temporal')
    if args.faults:
        assert info(cycle1)['status']=='WORKFLOW_EXECUTION_STATUS_RUNNING'
        compose('restart','temporal')
        wait(lambda:info(cycle1)['status']=='WORKFLOW_EXECUTION_STATUS_RUNNING','persistent workflow after Temporal restart')
        compose('start','workflow-worker');stopped.discard('workflow-worker')
    wait(lambda:completed(cycle1),'rejected workflow completed')
    assert result(cycle1)=='REJECTED'
    mutate('submission',{})
    withdrawn_cycle=sql("SELECT review_cycle_id FROM missions WHERE id='"+mid+"'")
    wait(lambda:info(withdrawn_cycle)['status']=='WORKFLOW_EXECUTION_STATUS_RUNNING','withdrawal workflow started')
    if args.faults:
        compose('stop','workflow-worker');stopped.add('workflow-worker')
    mutate('withdrawal',{'reason':'Périmètre à corriger avant nouvelle revue'})
    assert mission['status']=='IN_PROGRESS'
    wait(lambda:sql("SELECT count(*) FROM review_workflow_outbox WHERE cycle_id='"+withdrawn_cycle+"' AND published_at IS NULL")=='0','withdrawal accepted')
    if args.faults:
        assert info(withdrawn_cycle)['status']=='WORKFLOW_EXECUTION_STATUS_RUNNING'
        compose('start','workflow-worker');stopped.discard('workflow-worker')
    wait(lambda:completed(withdrawn_cycle),'withdrawn workflow completed')
    assert result(withdrawn_cycle)=='WITHDRAWN'
    if args.faults:
        compose('stop','notification-service');stopped.add('notification-service')
        compose('stop','temporal');stopped.add('temporal')
    mutate('submission',{})
    cycle2=sql("SELECT review_cycle_id FROM missions WHERE id='"+mid+"'")
    assert cycle2 not in (cycle1,withdrawn_cycle)
    mutate('review',{'decision':'APPROVED','comment':'Validation synthétique'},validator)
    mutate('status',{'status':'CLOSED'})
    if args.faults:
        assert sql("SELECT count(*) FROM review_workflow_outbox WHERE cycle_id='"+cycle2+"' AND published_at IS NULL")=='2'
        compose('restart','audit-service')
        wait(lambda:request('/missions/'+mid,manager)[0]==200,'API restart while Temporal down')
        compose('start','temporal');stopped.discard('temporal')
    wait(lambda:completed(cycle2),'approved workflow completed')
    assert result(cycle2)=='APPROVED'
    if args.faults:
        compose('start','notification-service');stopped.discard('notification-service')
    wait(lambda:len([n for n in request('/notifications',validator)[1].get('items',[]) if n['missionId']==mid and n['eventType']=='audit.mission.review-requested.v1'])==3,'three notifications without replay duplicates')
    run=info(cycle2)['execution']['runId']
    # Simulate the loss of delivery acknowledgements, including for a completed workflow.
    sql("UPDATE review_workflow_outbox SET published_at=NULL,available_at=now(),lease_until=NULL,lease_token=NULL WHERE cycle_id='"+cycle2+"'")
    wait(lambda:sql("SELECT count(*) FROM review_workflow_outbox WHERE cycle_id='"+cycle2+"' AND published_at IS NULL")=='0','duplicate deliveries acknowledged')
    assert info(cycle2)['execution']['runId']==run
    assert result(cycle2)=='APPROVED'
    print('PASS : trois cycles, dont un retrait, décision durable, reprise et rejeu sans nouveau workflow.' if args.faults else 'PASS : trois cycles, dont un retrait et rejeu sans nouveau workflow.')
finally:
    for service in sorted(stopped): compose('start',service)
    manifest=root/'.local'/('temporal-test-'+mid+'.txt')
    manifest.parent.mkdir(exist_ok=True)
    manifest.write_text(mid+'\n')
    manifest.chmod(0o600)
    subprocess.run(['python3',str(root/'scripts/cleanup-test-data.py'),str(manifest)],check=True)
