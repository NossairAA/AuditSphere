import json
import os
import subprocess
import unittest
from pathlib import Path

from keycloak_production import reject_demo_users


class ProductionRealmTest(unittest.TestCase):
    def test_import_has_no_users_or_demo_client(self):
        root = Path(__file__).resolve().parents[1]
        realm = json.loads((root / "deploy/keycloak/production-realm.json").read_text())
        self.assertEqual(realm["users"], [])
        self.assertNotIn("auditsphere-demo", [client["clientId"] for client in realm["clients"]])
        self.assertEqual(realm["sslRequired"], "all")

    def test_existing_demo_user_stops_provisioning(self):
        def call(path, token=None):
            name = path.split("username=")[1].split("&")[0]
            return [{"username": name}] if name == "superadmin" else []

        with self.assertRaisesRegex(RuntimeError, "superadmin"):
            reject_demo_users(call, "test-token")

    def test_clean_realm_passes(self):
        reject_demo_users(lambda path, token=None: [], "test-token")

    def test_production_compose_has_isolated_project_and_import(self):
        root = Path(__file__).resolve().parents[1]
        environment = os.environ.copy()
        environment.update({
            "PROD_OIDC_ORIGIN": "https://id.example.invalid",
            "PROD_APP_ORIGIN": "https://app.example.invalid",
            "KEYCLOAK_TLS_DIR": "/tmp/auditsphere-test-certs",
            "KEYCLOAK_DB_PASSWORD": "test",
            "KEYCLOAK_ADMIN_PASSWORD": "test",
            "WEB_DB_PASSWORD": "test",
            "AUDIT_DB_PASSWORD": "test",
            "NOTIFICATION_DB_PASSWORD": "test",
            "DIRECTORY_CLIENT_SECRET": "test",
        })
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "-f", "compose.yaml",
             "-f", "compose.production.yaml", "config", "--format", "json"],
            cwd=root, env=environment, text=True, capture_output=True, check=True,
        )
        config = json.loads(result.stdout)
        self.assertEqual(config["name"], "auditsphere-production")
        volumes = config["services"]["keycloak-import"]["volumes"]
        import_source = next(volume["source"] for volume in volumes if volume["target"] == "/source/realm.json")
        self.assertEqual(import_source, str(root / "deploy/keycloak/production-realm.json"))
        self.assertNotIn(".local/realm.json", result.stdout)


if __name__ == '__main__':
    unittest.main()
