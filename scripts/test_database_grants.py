"""Regression checks for immutable administration audit columns.

Set AUDITSPHERE_TEST_LOCAL_GRANTS=1 to verify provisioned local PostgreSQL roles.
"""
import os
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MUTABLE = ('outcome', 'completed_at', 'target_subject', 'requested_changes')
IMMUTABLE = ('actor_subject', 'action', 'reason', 'created_at')


class AdministrationGrantsTest(unittest.TestCase):
    def test_provisioning_never_grants_table_update_on_admin_log(self):
        source = (ROOT / 'scripts/harden-databases.py').read_text()
        grants = re.findall(r"GRANT ([^']+) TO audit_runtime", source)
        admin_grants = [grant for grant in grants if 'user_admin_log' in grant]
        self.assertEqual(len(admin_grants), 1)
        self.assertIn('UPDATE(' + ','.join(MUTABLE) + ') ON user_admin_log', admin_grants[0])
        self.assertNotRegex(admin_grants[0], r'UPDATE\s+ON')
        self.assertIn('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM {runtime}', source)
        self.assertIn('GRANT SELECT,INSERT,UPDATE ON account_revocations TO audit_runtime', source)

    def test_provisioning_keeps_finding_log_append_only(self):
        source = (ROOT / 'scripts/harden-databases.py').read_text()
        grants = re.findall(r"GRANT ([^']+) TO audit_runtime", source)
        log_grants = [grant for grant in grants if 'finding_change_log' in grant]
        self.assertEqual(log_grants, ['SELECT,INSERT ON finding_change_log'])
        self.assertIn("'mission_findings','mission_finding_controls','mission_finding_sequences')", source)

    def test_provisioning_keeps_remediation_trace_append_only(self):
        source = (ROOT / 'scripts/harden-databases.py').read_text()
        grants = re.findall(r"GRANT ([^']+) TO remediation_runtime", source)
        self.assertEqual(grants, ['SELECT,INSERT ON %I', 'SELECT,INSERT,UPDATE,DELETE ON %I'])
        append_only, writable = re.findall(r"TO remediation_runtime',tablename\) FROM pg_tables WHERE schemaname='public' AND tablename IN \(([^)]*)\)", source)
        self.assertEqual(append_only, "'remediation_change_log','action_progress_notes'")
        self.assertNotIn('change_log', writable)
        self.assertNotIn('progress_notes', writable)
        migration = (ROOT / 'services/remediation-service/src/main/resources/db/migration/V1__remediation.sql').read_text()
        tables = set(re.findall(r'CREATE TABLE (\w+)', migration))
        granted = set(re.findall(r"'(\w+)'", append_only + ',' + writable))
        self.assertEqual(tables, granted, 'every remediation table needs an explicit runtime grant')

    @unittest.skipUnless(os.environ.get('AUDITSPHERE_TEST_LOCAL_GRANTS') == '1', 'local PostgreSQL verification is opt-in')
    def test_local_remediation_runtime_cannot_rewrite_the_trace(self):
        checks = [f"has_table_privilege('remediation_runtime','{table}','{privilege}')"
                  for table in ('remediation_change_log', 'action_progress_notes') for privilege in ('INSERT', 'UPDATE', 'DELETE')]
        checks += ["has_table_privilege('remediation_runtime','corrective_actions','UPDATE')"]
        result = subprocess.run(
            ['docker', 'compose', 'exec', '-T', 'remediation-db', 'psql', '-U', 'remediation', '-d', 'remediation', '-At', '-v', 'ON_ERROR_STOP=1'],
            input='SELECT ' + ','.join(checks) + ';\n', text=True, capture_output=True, cwd=ROOT,
        )
        self.assertEqual(result.returncode, 0, 'Local privilege query failed')
        self.assertEqual(result.stdout.strip().split('|'), ['t', 'f', 'f', 't', 'f', 'f', 't'])

    @unittest.skipUnless(os.environ.get('AUDITSPHERE_TEST_LOCAL_GRANTS') == '1', 'local PostgreSQL verification is opt-in')
    def test_local_runtime_cannot_update_actor_but_can_complete_operation(self):
        checks = ["has_table_privilege('audit_runtime','user_admin_log','UPDATE')"]
        checks += [f"has_column_privilege('audit_runtime','user_admin_log','{column}','UPDATE')" for column in IMMUTABLE + MUTABLE]
        checks += ["has_table_privilege('audit_runtime','account_revocations','UPDATE')"]
        result = subprocess.run(
            ['docker', 'compose', 'exec', '-T', 'audit-db', 'psql', '-U', 'audit', '-d', 'audit', '-At', '-v', 'ON_ERROR_STOP=1'],
            input='SELECT ' + ','.join(checks) + ';\n', text=True, capture_output=True, cwd=ROOT,
        )
        self.assertEqual(result.returncode, 0, 'Local privilege query failed')
        self.assertEqual(result.stdout.strip().split('|'), ['f'] * (1 + len(IMMUTABLE)) + ['t'] * (1 + len(MUTABLE)))


if __name__ == '__main__':
    unittest.main()
