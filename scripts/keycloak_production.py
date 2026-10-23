"""Fail-closed production realm checks."""

DEMO_USERNAMES = ("manager", "auditor", "outsider", "validator", "superadmin")
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


