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
