import json
import os
import subprocess
import unittest
from pathlib import Path

from keycloak_production import MFA_FLOW, PASSWORD_POLICY, configure_mfa, disable_direct_grants, reject_demo_users


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
            "REMEDIATION_DB_PASSWORD": "test",
            "DIRECTORY_CLIENT_SECRET": "test",
        })
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "-f", "compose.yaml",
             "-f", "compose.production.yaml", "config", "--format", "json"],
            cwd=root, env=environment, text=True, capture_output=True, check=True,
        )
        config = json.loads(result.stdout)
        self.assertEqual(config["name"], "auditsphere-production")
        self.assertNotIn("ports", config["services"]["remediation-db"])
        volumes = config["services"]["keycloak-import"]["volumes"]
        import_source = next(volume["source"] for volume in volumes if volume["target"] == "/source/realm.json")
        self.assertEqual(import_source, str(root / "deploy/keycloak/production-realm.json"))
        self.assertNotIn(".local/realm.json", result.stdout)


class ProductionMfaTest(unittest.TestCase):
    def test_password_grant_is_disabled_for_built_in_and_custom_clients(self):
        clients = [
            {"id": "1", "clientId": "admin-cli", "directAccessGrantsEnabled": True},
            {"id": "2", "clientId": "auditsphere-web", "directAccessGrantsEnabled": False},
            {"id": "3", "clientId": "legacy", "directAccessGrantsEnabled": True},
        ]

        def call(path, data=None, method=None, token=None):
            self.assertEqual(token, "test-token")
            if path.endswith("?first=0&max=100"):
                return clients
            if method == "PUT":
                next(client for client in clients if client["id"] == path.rsplit("/", 1)[1]).update(data)
                return None
            raise AssertionError(path)

        disable_direct_grants(call, "test-token")
        self.assertTrue(all(not client["directAccessGrantsEnabled"] for client in clients))

    def test_browser_flow_requires_otp_and_password_policy(self):
        base = "/admin/realms/auditsphere"
        flow = base + "/authentication/flows/" + MFA_FLOW
        forms = base + "/authentication/flows/auditsphere-browser-mfa%20forms/executions"
        state = {
            "flows": [],
            "realm": {},
            "form_executions": [
                {"id": "conditional", "displayName": "Conditional 2FA", "authenticationFlow": True,
                 "requirement": "CONDITIONAL"}
            ],
        }

        def call(path, data=None, method=None, token=None):
            self.assertEqual(token, "test-token")
            if path == base + "/authentication/flows":
                return state["flows"]
            if path.endswith("/browser/copy"):
                state["flows"].append({"alias": MFA_FLOW})
                return None
            if path == flow + "/executions":
                return [{"id": "forms", "displayName": MFA_FLOW + " forms", "authenticationFlow": True}]
            if path == forms:
                if method == "PUT":
                    for index, item in enumerate(state["form_executions"]):
                        if item["id"] == data["id"]:
                            state["form_executions"][index] = data
                    return None
                return state["form_executions"]
            if path == forms + "/execution":
                state["form_executions"].append(
                    {"id": "otp", "providerId": "auth-otp-form", "requirement": "DISABLED"}
                )
                return None
            if path == base:
                if method == "PUT":
                    state["realm"].update(data)
                    return None
                return state["realm"]
            raise AssertionError(path)

        configure_mfa(call, "test-token")
        self.assertEqual(state["realm"], {"browserFlow": MFA_FLOW, "passwordPolicy": PASSWORD_POLICY})
        self.assertEqual(next(x for x in state["form_executions"] if x["id"] == "otp")["requirement"], "REQUIRED")
        self.assertEqual(next(x for x in state["form_executions"] if x["id"] == "conditional")["requirement"], "DISABLED")
        configure_mfa(call, "test-token")
        self.assertEqual(len([x for x in state["form_executions"] if x["id"] == "otp"]), 1)


if __name__ == "__main__":
    unittest.main()
