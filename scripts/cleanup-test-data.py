#!/usr/bin/env python3
"""Remove only explicitly recorded synthetic mission IDs from the local Compose stack or from k3s."""
import argparse, json, os, subprocess, time, uuid
from pathlib import Path
from kafka_cleanup import offsets_drained
from service_health import wait_healthy
import target
root=target.ROOT
parser=argparse.ArgumentParser()
parser.add_argument('manifest',type=Path)
args=parser.parse_args()
manifest=args.manifest.resolve()
ids=sorted({str(uuid.UUID(line.strip())) for line in manifest.read_text().splitlines() if line.strip()})
if not ids: raise SystemExit(0)
values=','.join("'"+v+"'" for v in ids)
def compose(*cmd,check=True,input=None):
 return subprocess.run(['docker','compose',*cmd],cwd=root,text=True,capture_output=True,check=check,input=input)
sql=target.sql
os.umask(0o077)
backup=root/'.local'/'backups'/('test-cleanup-'+str(uuid.uuid4()))
backup.mkdir(parents=True)
# Preserve evidence and allow recovery before deleting anything.
# The remediation base exists since F-006: older stacks are cleaned without it.
remediation=sql('remediation',"SELECT to_regclass('public.mission_scopes') IS NOT NULL")=='t'
for db in ['audit','notification']+(['remediation'] if remediation else []):
 with (backup/(db+'.dump')).open('wb') as f: target.dump(db,f)
(backup/'mission-ids.txt').write_text('\n'.join(ids)+'\n')
# remediation-service publishes action assignments: stopped too, so the notification drain covers them.
producers=target.running(['audit-service','workflow-worker','remediation-service'])
try:
 if producers: target.stop(*producers)
 # Drain existing Kafka records before removing their notifications. Never reset shared offsets.
 deadline=time.monotonic()+45
 while True:
  output=target.kafka('kafka-consumer-groups.sh','--bootstrap-server',target.KAFKA_BOOTSTRAP,'--describe','--group','notification-service-v1').stdout
  if offsets_drained(output): break
  if time.monotonic()>deadline: raise RuntimeError('Kafka non vidé, nettoyage abandonné sans suppression')
  time.sleep(1)
 cycles=sql('audit',f"SELECT DISTINCT cycle_id FROM review_workflow_outbox WHERE mission_id IN ({values})")
 for cycle in cycles.splitlines():
  workflow='mission-review-'+str(uuid.UUID(cycle))
  result=target.temporal('workflow','describe','--workflow-id',workflow,'--output','json',check=False)
  if result.returncode:
   if 'not found' not in (result.stdout+result.stderr).lower(): raise RuntimeError('Impossible de vérifier le workflow de test')
  else:
   (backup/(cycle+'.json')).write_text(target.temporal('workflow','show','--workflow-id',workflow,'--output','json').stdout)
   target.temporal('workflow','delete','--workflow-id',workflow,input='y\n')
 if sql('audit',"SELECT to_regclass('public.control_evidence') IS NOT NULL") == 't':
  from evidence_storage import request as storage_request
  keys=sql('audit',f"SELECT object_key FROM control_evidence WHERE mission_id IN ({values})")
  # Evidence keys are signed with the local .env only: never delete cluster objects with them.
  if keys and target.K3S: raise RuntimeError('Preuves présentes dans k3s, nettoyage abandonné sans suppression')
  for key in keys.splitlines(): storage_request('DELETE',key)
 program_cleanup = f"DELETE FROM mission_control_risks WHERE mission_id IN ({values}); " if sql('audit',"SELECT to_regclass('public.mission_control_risks') IS NOT NULL") == 't' else ''
 if sql('audit',"SELECT to_regclass('public.mission_finding_controls') IS NOT NULL") == 't':
  # Findings go before controls. Their append-only log is kept like the program log.
  program_cleanup = f"DELETE FROM mission_finding_controls WHERE mission_id IN ({values}); DELETE FROM mission_findings WHERE mission_id IN ({values}); DELETE FROM mission_finding_sequences WHERE mission_id IN ({values}); " + program_cleanup
 if sql('audit',"SELECT to_regclass('public.findings_issue_outbox') IS NOT NULL") == 't':
  program_cleanup += f"DELETE FROM findings_issue_outbox WHERE mission_id IN ({values}); "
 sql('audit',f"BEGIN; {program_cleanup}DELETE FROM assignment_outbox WHERE mission_id IN ({values}); DELETE FROM review_workflow_outbox WHERE mission_id IN ({values}); DELETE FROM mission_assignments WHERE mission_id IN ({values}); DELETE FROM missions WHERE id IN ({values}); COMMIT;")
 if remediation:
  keys=sql('remediation',f"SELECT object_key FROM action_attachments WHERE mission_id IN ({values})")
  if keys and target.K3S: raise RuntimeError('Justificatifs présents dans k3s, nettoyage abandonné sans suppression')
  from evidence_storage import request as storage_request
  for key in keys.splitlines(): storage_request('DELETE',key,prefix='REMEDIATION')
  # Same order as demo-reset. remediation_change_log and processed_events are kept.
  actions=f"SELECT id FROM corrective_actions WHERE mission_id IN ({values})"
  follow_ups=f"SELECT id FROM finding_follow_ups WHERE mission_id IN ({values})"
  sql('remediation',f"BEGIN; DELETE FROM remediation_outbox WHERE mission_id IN ({values}); DELETE FROM action_attachments WHERE mission_id IN ({values}); DELETE FROM action_progress_notes WHERE action_id IN ({actions}); DELETE FROM corrective_actions WHERE mission_id IN ({values}); DELETE FROM action_sequences WHERE follow_up_id IN ({follow_ups}); DELETE FROM finding_follow_ups WHERE mission_id IN ({values}); DELETE FROM mission_scope_auditors WHERE mission_id IN ({values}); DELETE FROM mission_scopes WHERE mission_id IN ({values}); COMMIT;")
 sql('notification',f"DELETE FROM notifications WHERE mission_id IN ({values})")
 # Keep mission_action_log, including evidence of draft deletion.
 manifest.unlink()
 print(f'Nettoyage : {len(ids)} identifiants de test, sauvegarde privée créée.')
finally:
 if producers: target.start(*producers)
 # Callers chain suites right after cleanup: hand back a stack whose API answers again.
 # In k3s, target.start already waits for the readiness probe.
 if 'audit-service' in producers and not target.K3S:
  probe='exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\\r\\n\\r\\n" >&3; head -1 <&3'
  wait_healthy(lambda:compose('exec','-T','audit-service','bash','-c',probe,check=False).stdout,'audit-service')
