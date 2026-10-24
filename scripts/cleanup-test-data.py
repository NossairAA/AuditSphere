#!/usr/bin/env python3
"""Remove only explicitly recorded synthetic mission IDs from the local Compose stack."""
import argparse, json, os, subprocess, time, uuid
from pathlib import Path
from kafka_cleanup import offsets_drained
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('manifest',type=Path)
args=parser.parse_args()
manifest=args.manifest.resolve()
ids=sorted({str(uuid.UUID(line.strip())) for line in manifest.read_text().splitlines() if line.strip()})
if not ids: raise SystemExit(0)
values=','.join("'"+v+"'" for v in ids)
def compose(*cmd,check=True,input=None):
 return subprocess.run(['docker','compose',*cmd],cwd=root,text=True,capture_output=True,check=check,input=input)
def sql(db,query):
 return compose('exec','-T',db+'-db','psql','-X','-At','-v','ON_ERROR_STOP=1','-U',db+'_admin','-d',db,'-c',query).stdout.strip()
os.umask(0o077)
backup=root/'.local'/'backups'/('test-cleanup-'+str(uuid.uuid4()))
backup.mkdir(parents=True)
# Preserve evidence and allow recovery before deleting anything.
for db in ['audit','notification']:
 with (backup/(db+'.dump')).open('wb') as f:
  subprocess.run(['docker','compose','exec','-T',db+'-db','pg_dump','-U',db+'_admin','-d',db,'-Fc'],cwd=root,stdout=f,check=True)
(backup/'mission-ids.txt').write_text('\n'.join(ids)+'\n')
running=set(compose('ps','--services','--status','running').stdout.split())
producers=[s for s in ['audit-service','workflow-worker'] if s in running]
try:
 if producers: compose('stop',*producers)
 # Drain existing Kafka records before removing their notifications. Never reset shared offsets.
 deadline=time.monotonic()+45
 while True:
  output=compose('exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:9092','--describe','--group','notification-service-v1').stdout
  if offsets_drained(output): break
  if time.monotonic()>deadline: raise RuntimeError('Kafka non vidé, nettoyage abandonné sans suppression')
  time.sleep(1)
 cycles=sql('audit',f"SELECT DISTINCT cycle_id FROM review_workflow_outbox WHERE mission_id IN ({values})")
 for cycle in cycles.splitlines():
  workflow='mission-review-'+str(uuid.UUID(cycle))
  result=compose('exec','-T','temporal','temporal','workflow','describe','--workflow-id',workflow,'--output','json',check=False)
  if result.returncode:
   if 'not found' not in (result.stdout+result.stderr).lower(): raise RuntimeError('Impossible de vérifier le workflow de test')
  else:
   (backup/(cycle+'.json')).write_text(compose('exec','-T','temporal','temporal','workflow','show','--workflow-id',workflow,'--output','json').stdout)
   compose('exec','-T','temporal','temporal','workflow','delete','--workflow-id',workflow,input='y\n')
 if sql('audit',"SELECT to_regclass('public.control_evidence') IS NOT NULL") == 't':
  from evidence_storage import request as storage_request
  keys=sql('audit',f"SELECT object_key FROM control_evidence WHERE mission_id IN ({values})")
  for key in keys.splitlines(): storage_request('DELETE',key)
 program_cleanup = f"DELETE FROM mission_control_risks WHERE mission_id IN ({values}); " if sql('audit',"SELECT to_regclass('public.mission_control_risks') IS NOT NULL") == 't' else ''
 sql('audit',f"BEGIN; {program_cleanup}DELETE FROM assignment_outbox WHERE mission_id IN ({values}); DELETE FROM review_workflow_outbox WHERE mission_id IN ({values}); DELETE FROM mission_assignments WHERE mission_id IN ({values}); DELETE FROM missions WHERE id IN ({values}); COMMIT;")
 sql('notification',f"DELETE FROM notifications WHERE mission_id IN ({values})")
 # Keep mission_action_log, including evidence of draft deletion.
 manifest.unlink()
 print(f'Nettoyage : {len(ids)} identifiants de test, sauvegarde privée créée.')
finally:
 if producers: compose('start',*producers)
 # Callers chain suites right after cleanup: hand back a stack whose API answers again.
 if 'audit-service' in producers:
  probe='exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\\r\\n\\r\\n" >&3; head -1 <&3'
  deadline=time.monotonic()+120
  while ' 200' not in compose('exec','-T','audit-service','bash','-c',probe,check=False).stdout:
   if time.monotonic()>deadline: raise RuntimeError('audit-service indisponible après nettoyage')
   time.sleep(1)
