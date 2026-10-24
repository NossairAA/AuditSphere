"""Fail-closed checks and production authentication configuration for Keycloak."""

from urllib.parse import quote

DEMO_USERNAMES = ("manager", "auditor", "outsider", "validator", "superadmin", "auditee")
PASSWORD_POLICY = (
    "length(14) and upperCase(1) and lowerCase(1) and digits(1) "
    "and specialChars(1) and notUsername and notEmail and passwordHistory(5)"
)
MFA_FLOW = "auditsphere-browser-mfa"


def reject_demo_users(call, token):
    base = "/admin/realms/auditsphere"
    present = []
    for username in DEMO_USERNAMES:
        users = call(f"{base}/users?username={username}&exact=true", token=token)
        if any(user.get("username") == username for user in users):
            present.append(username)
    if present:
        raise RuntimeError(
            "Provisionnement refusé : comptes de démonstration présents dans le royaume de production : "
            + ", ".join(present)
        )


def disable_direct_grants(call, token):
    """The password grant does not pass through the realm's browser OTP flow."""
    base = "/admin/realms/auditsphere/clients"

    def clients():
        result = []
        for first in range(0, 1000, 100):
            batch = call(f"{base}?first={first}&max=100", token=token)
            result.extend(batch)
            if len(batch) < 100:
                return result
        raise RuntimeError("Trop de clients Keycloak pour vérifier les grants directs.")

    for client in clients():
        if client.get("directAccessGrantsEnabled"):
            call(base + "/" + client["id"], {"directAccessGrantsEnabled": False}, "PUT", token)
    if any(client.get("directAccessGrantsEnabled") for client in clients()):
        raise RuntimeError("Un client Keycloak conserve le grant mot de passe en production.")


def configure_mfa(call, token):
    """Copy the browser flow and require OTP for every production user."""
    base = "/admin/realms/auditsphere"
    auth = base + "/authentication"
    flows = call(auth + "/flows", token=token)
    if not any(flow.get("alias") == MFA_FLOW for flow in flows):
        call(auth + "/flows/browser/copy", {"newName": MFA_FLOW}, "POST", token)
    executions = call(auth + "/flows/" + MFA_FLOW + "/executions", token=token)
    forms = [execution for execution in executions if execution.get("authenticationFlow")
             and execution.get("displayName", "").endswith(" forms")]
    if len(forms) != 1:
        raise RuntimeError("Flux Keycloak inattendu : sous-flux forms introuvable.")
    forms_alias = forms[0]["displayName"]
    forms_path = auth + "/flows/" + quote(forms_alias, safe="") + "/executions"
    form_executions = call(forms_path, token=token)
    conditional = [execution for execution in form_executions
                   if execution.get("authenticationFlow") and "Conditional 2FA" in execution.get("displayName", "")]
    if len(conditional) != 1:
        raise RuntimeError("Flux Keycloak inattendu : sous-flux Conditional 2FA introuvable.")
    if conditional[0].get("requirement") != "DISABLED":
        call(forms_path, {**conditional[0], "requirement": "DISABLED"}, "PUT", token)
    otp = [execution for execution in call(forms_path, token=token)
           if execution.get("providerId") == "auth-otp-form"]
    if not otp:
        call(forms_path + "/execution", {"provider": "auth-otp-form"}, "POST", token)
        otp = [execution for execution in call(forms_path, token=token)
               if execution.get("providerId") == "auth-otp-form"]
    if len(otp) != 1:
        raise RuntimeError("Flux Keycloak inattendu : exécution OTP introuvable.")
    if otp[0].get("requirement") != "REQUIRED":
        call(forms_path, {**otp[0], "requirement": "REQUIRED"}, "PUT", token)
    call(base, {"browserFlow": MFA_FLOW, "passwordPolicy": PASSWORD_POLICY}, "PUT", token)
    realm = call(base, token=token)
    verified = call(forms_path, token=token)
    if realm.get("browserFlow") != MFA_FLOW or realm.get("passwordPolicy") != PASSWORD_POLICY:
        raise RuntimeError("La politique de production Keycloak n'a pas été appliquée.")
    if not any(execution.get("providerId") == "auth-otp-form" and execution.get("requirement") == "REQUIRED"
               for execution in verified):
        raise RuntimeError("OTP obligatoire non confirmé dans le flux Keycloak.")
