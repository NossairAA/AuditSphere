set -eu
umask 077
export PGHOST=postgres.data.svc.cluster.local
# TLS vérifié contre la racine hors ligne (phase 8, lot C).
export PGSSLMODE=verify-full PGSSLROOTCERT=/etc/auditsphere/ca/ca.crt
for _ in $(seq 30); do pg_isready -q && break; sleep 2; done
sql() { PGUSER="$1_migrator" PGPASSWORD="$2" psql -X -At -v ON_ERROR_STOP=1 -d "$1" -c "$3"; }
# Clés des preuves, retirées du stockage par le conteneur seed. Le journal mission_action_log et les journaux
# de modification sont conservés, comme dans scripts/cleanup-test-data.py.
sql audit "$AUDIT_PASSWORD" "SELECT object_key FROM control_evidence" > /work/evidence-keys.txt
sql audit "$AUDIT_PASSWORD" "BEGIN;
  DELETE FROM mission_finding_controls; DELETE FROM mission_findings; DELETE FROM mission_finding_sequences;
  DELETE FROM findings_issue_outbox; DELETE FROM control_evidence; DELETE FROM mission_control_risks;
  DELETE FROM mission_controls; DELETE FROM mission_risks; DELETE FROM assignment_outbox;
  DELETE FROM review_workflow_outbox; DELETE FROM mission_assignments; DELETE FROM missions;
  COMMIT;"
sql notification "$NOTIFICATION_PASSWORD" "DELETE FROM notifications"
sql remediation "$REMEDIATION_PASSWORD" "BEGIN;
  DELETE FROM remediation_outbox; DELETE FROM action_attachments; DELETE FROM action_progress_notes;
  DELETE FROM corrective_actions; DELETE FROM action_sequences; DELETE FROM finding_follow_ups;
  DELETE FROM mission_scope_auditors; DELETE FROM mission_scopes;
  COMMIT;"
echo "bases : missions, notifications et suivis retirés, $(wc -l < /work/evidence-keys.txt) preuves à retirer"
