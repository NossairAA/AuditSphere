#!/usr/bin/env python3
"""Configure idempotently the Keycloak of the k3s cluster for the demo profile (phase 4, lot D, hardened in phase 10).

Secrets come from the cluster and demo passwords from .local/k3s-demo-accounts.env. Nothing secret is printed.
"""
from pathlib import Path
import base64, json, os, secrets, subprocess, sys, urllib.error, urllib.parse, urllib.request

root = Path(__file__).resolve().parents[1]
os.umask(0o077)
NAMESPACE = 'auditsphere'
os.environ.setdefault('KUBECONFIG', str(Path.home() / '.kube' / 'auditsphere.yaml'))


def read_env(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))


local = read_env(root / '.local' / 'k3s.env')
app_origin, identity_origin = local['APP_ORIGIN'], local['OIDC_ORIGIN']
# The admin API and the master realm are refused on the public name (ADR-0033): use the tailnet name when set.
admin_origin = local.get('KEYCLOAK_ADMIN_ORIGIN') or identity_origin
if not all(origin.startswith('https://') for origin in (app_origin, identity_origin, admin_origin)):
    raise SystemExit('APP_ORIGIN, OIDC_ORIGIN et KEYCLOAK_ADMIN_ORIGIN HTTPS sont obligatoires dans .local/k3s.env.')


def kubectl(*args, stdin=None):
    result = subprocess.run(['kubectl', '-n', NAMESPACE, *args], input=stdin, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit('kubectl ' + args[0] + ' a échoué.')
    return result.stdout


def secret(name):
    """Return the decoded secret, or None if absent. Values never leave this process."""
    result = subprocess.run(['kubectl', '-n', NAMESPACE, 'get', 'secret', name, '-o', 'json'], text=True, capture_output=True)
    if result.returncode:
        if 'NotFound' in result.stderr:
            return None
        raise SystemExit('Cluster injoignable par kubectl. Vérifier Tailscale et tout VPN concurrent.')
    return {k: base64.b64decode(v).decode() for k, v in json.loads(result.stdout)['data'].items()}


def save_secret(name, values):
    data = {k: base64.b64encode(v.encode()).decode() for k, v in values.items()}
    kubectl('apply', '-f', '-', stdin=json.dumps({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': NAMESPACE}, 'type': 'Opaque', 'data': data}))


def call(path, data=None, method=None, token=None, form=False):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    payload = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
    request = urllib.request.Request(admin_origin + path, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


def token_for(form):
    try:
        return call('/realms/master/protocol/openid-connect/token', form, form=True)['access_token']
    except urllib.error.HTTPError:
        return None


# Automation uses a master client with client credentials, humans use kc-admin with OTP.
provisioner = secret('keycloak-provisioner')
token = provisioner and token_for({'grant_type': 'client_credentials', 'client_id': 'k3s-provisioner', 'client_secret': provisioner['client_secret']})
if not token:
    bootstrap = secret('keycloak-admin')
    token = bootstrap and token_for({'grant_type': 'password', 'client_id': 'admin-cli', 'username': bootstrap['username'], 'password': bootstrap['password']})
    if not token:
        raise SystemExit('Aucun accès administrateur : ni client k3s-provisioner, ni administrateur d’amorçage valide.')
    client_secret = secrets.token_urlsafe(32)
    client = {'clientId': 'k3s-provisioner', 'protocol': 'openid-connect', 'publicClient': False, 'secret': client_secret,
              'standardFlowEnabled': False, 'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': True}
    existing = call('/admin/realms/master/clients?clientId=k3s-provisioner', token=token)
    if existing:
        call('/admin/realms/master/clients/' + existing[0]['id'], client, 'PUT', token)
    else:
        call('/admin/realms/master/clients', client, 'POST', token)
    client_id = call('/admin/realms/master/clients?clientId=k3s-provisioner', token=token)[0]['id']
    account = call('/admin/realms/master/clients/' + client_id + '/service-account-user', token=token)
    admin_role = call('/admin/realms/master/roles/admin', token=token)
    call('/admin/realms/master/users/' + account['id'] + '/role-mappings/realm', [admin_role], 'POST', token)
    save_secret('keycloak-provisioner', {'client_secret': client_secret})
    token = token_for({'grant_type': 'client_credentials', 'client_id': 'k3s-provisioner', 'client_secret': client_secret})
    if not token:
        raise SystemExit('Le client k3s-provisioner ne s’authentifie pas.')
    print('Client d’automatisation k3s-provisioner créé, secret stocké dans le cluster.')

# Demo passwords stay on the workstation, outside Git, and are never printed.
accounts_file = root / '.local' / 'k3s-demo-accounts.env'
accounts = read_env(accounts_file) if accounts_file.exists() else {}
names = ['KC_ADMIN', 'MANAGER', 'AUDITOR', 'OUTSIDER', 'VALIDATOR', 'SUPERADMIN', 'AUDITEE']
missing = {name + '_PASSWORD': secrets.token_urlsafe(24) for name in names if name + '_PASSWORD' not in accounts}
if missing:
    accounts.update(missing)
    accounts_file.write_text(''.join(k + '=' + v + '\n' for k, v in accounts.items()))
    accounts_file.chmod(0o600)


def ensure_user(realm, username, email, first, last, password, roles=(), required=()):
    base = '/admin/realms/' + realm
    users = call(base + '/users?username=' + username + '&exact=true', token=token)
    if not users:
        call(base + '/users', {'username': username, 'enabled': True, 'emailVerified': True, 'firstName': first, 'lastName': last, 'email': email,
                               'requiredActions': list(required), 'credentials': [{'type': 'password', 'value': password, 'temporary': False}]}, 'POST', token)
        users = call(base + '/users?username=' + username + '&exact=true', token=token)
    # Never reset a password or add a role to an unrelated existing account.
    if users[0].get('email') != email:
        raise SystemExit('Le compte ' + username + ' existe avec une autre identité. Droits inchangés.')
    if roles:
        call(base + '/users/' + users[0]['id'] + '/role-mappings/realm', [call(base + '/roles/' + r, token=token) for r in roles], 'POST', token)


ensure_user('master', 'kc-admin', 'kc-admin@example.invalid', 'Administration', 'Keycloak', accounts['KC_ADMIN_PASSWORD'], ['admin'], ['CONFIGURE_TOTP'])
bootstrap = secret('keycloak-admin')
if bootstrap:
    for user in call('/admin/realms/master/users?username=' + bootstrap['username'] + '&exact=true', token=token):
        call('/admin/realms/master/users/' + user['id'], method='DELETE', token=token)
print('Administrateur permanent kc-admin prêt, OTP exigé à la première connexion. Administrateur d’amorçage retiré.')

base = '/admin/realms/auditsphere'
for role, description in [('AUDIT_VALIDATOR', 'Validation des missions'), ('SUPER_ADMIN', 'Administration globale des missions et dérogations tracées'),
                          ('AUDITEE', 'Responsable d’actions correctives, sans accès aux missions'),
                          ('REMEDIATION_SYNC', 'Compte de service : lecture des constats émis, jamais attribué à une personne')]:
    try:
        call(base + '/roles/' + role, token=token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        call(base + '/roles', {'name': role, 'description': description}, 'POST', token)

demo = [('manager', 'Claire', 'Martin', ['AUDIT_MANAGER']), ('auditor', 'Julien', 'Moreau', ['AUDITOR']), ('outsider', 'Sarah', 'Benali', ['AUDITOR']),
        ('validator', 'Thomas', 'Laurent', ['AUDIT_VALIDATOR']), ('superadmin', 'Alexandre', 'Bernard', ['SUPER_ADMIN']), ('auditee', 'Nadia', 'Rousseau', ['AUDITEE'])]
for username, first, last, roles in demo:
    ensure_user('auditsphere', username, username + '@example.invalid', first, last, accounts[username.upper() + '_PASSWORD'], roles)
print('Six comptes de démonstration synthétiques prêts.')

audience = [{'name': 'api-audience', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-audience-mapper',
             'config': {'included.custom.audience': 'auditsphere-api', 'access.token.claim': 'true', 'id.token.claim': 'false'}}]


def ensure_client(client):
    existing = call(base + '/clients?clientId=' + client['clientId'], token=token)
    if existing:
        call(base + '/clients/' + existing[0]['id'], client, 'PUT', token)
    else:
        call(base + '/clients', client, 'POST', token)
    return call(base + '/clients?clientId=' + client['clientId'], token=token)[0]['id']


web, app = secret('web-secrets'), secret('app-secrets')
ensure_client({'clientId': 'auditsphere-web', 'name': 'AuditSphere Web', 'protocol': 'openid-connect', 'publicClient': False, 'secret': web['OIDC_CLIENT_SECRET'],
               'standardFlowEnabled': True, 'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': False,
               'redirectUris': [app_origin + '/auth/callback'], 'webOrigins': [app_origin],
               'attributes': {'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': app_origin + '/'}, 'protocolMappers': audience})
# Password grant for seeding and smoke tests. Disabled at rest, enabled by scripts/k3s-check.sh for one run only (ADR-0032).
ensure_client({'clientId': 'auditsphere-demo', 'enabled': False, 'protocol': 'openid-connect', 'publicClient': True, 'standardFlowEnabled': False,
               'directAccessGrantsEnabled': True, 'serviceAccountsEnabled': False, 'protocolMappers': audience})
print('Clients auditsphere-web, avec code et PKCE, et auditsphere-demo, désactivé au repos, configurés.')

management = call(base + '/clients?clientId=realm-management', token=token)[0]['id']


def service_account(client_id, management_roles=(), realm_roles=()):
    account = call(base + '/clients/' + client_id + '/service-account-user', token=token)
    if management_roles:
        call(base + '/users/' + account['id'] + '/role-mappings/clients/' + management,
             [call(base + '/clients/' + management + '/roles/' + r, token=token) for r in management_roles], 'POST', token)
    if realm_roles:
        call(base + '/users/' + account['id'] + '/role-mappings/realm', [call(base + '/roles/' + r, token=token) for r in realm_roles], 'POST', token)


server = {'protocol': 'openid-connect', 'publicClient': False, 'standardFlowEnabled': False, 'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': True}
service_account(ensure_client({**server, 'clientId': 'auditsphere-directory', 'secret': app['DIRECTORY_CLIENT_SECRET']}), ['view-users', 'view-realm'])
service_account(ensure_client({**server, 'clientId': 'auditsphere-user-admin', 'secret': app['USER_ADMIN_CLIENT_SECRET']}), ['view-users', 'query-users', 'manage-users', 'view-realm'])
service_account(ensure_client({**server, 'clientId': 'remediation-service', 'secret': app['REMEDIATION_SYNC_CLIENT_SECRET'], 'protocolMappers': audience}), realm_roles=['REMEDIATION_SYNC'])
print('Clients serveur annuaire, gestion des utilisateurs et remediation-service configurés avec les secrets du cluster.')

# Switch of auditsphere-demo, limited to the clients of this realm instead of the master administrator (ADR-0032).
switch = secret('keycloak-demo-switch') or {'client_secret': secrets.token_urlsafe(32)}
service_account(ensure_client({**server, 'clientId': 'k3s-demo-switch', 'secret': switch['client_secret']}), ['view-clients', 'manage-clients'])
save_secret('keycloak-demo-switch', switch)
print('Client k3s-demo-switch configuré, secret stocké dans le cluster.')

# Account console closed (ADR-0034): the account client serves /realms/auditsphere/account and its REST API, 404 once disabled.
for client_id in ['account', 'account-console']:
    client = call(base + '/clients?clientId=' + client_id, token=token)[0]
    call(base + '/clients/' + client['id'], {**client, 'enabled': False}, 'PUT', token)
# Actions started by kc_action on the sign-in page ask for the password again, even inside a single sign-on session.
for action in ['UPDATE_PASSWORD', 'CONFIGURE_TOTP', 'CONFIGURE_RECOVERY_AUTHN_CODES', 'webauthn-register', 'webauthn-register-passwordless',
               'delete_credential', 'UPDATE_PROFILE']:
    # Recovery codes refuse a configuration without their warning threshold, 4 by default.
    config = {'max_auth_age': '0', **({'warning_threshold': '4'} if action == 'CONFIGURE_RECOVERY_AUTHN_CODES' else {})}
    call(base + '/authentication/required-actions/' + action + '/config', {'config': config}, 'PUT', token)
print('Console de compte fermée, mot de passe redemandé avant toute action lancée par kc_action.')

# OTP for the super administrator, enrolled at the next browser sign-in. A configured OTP is left untouched.
superadmin = call(base + '/users?username=superadmin&exact=true', token=token)[0]
if not any(c['type'] == 'otp' for c in call(base + '/users/' + superadmin['id'] + '/credentials', token=token)):
    actions = sorted(set(superadmin.get('requiredActions', [])) | {'CONFIGURE_TOTP'})
    call(base + '/users/' + superadmin['id'], {'requiredActions': actions}, 'PUT', token)
    print('OTP exigé pour superadmin à sa prochaine connexion.')
else:
    print('OTP de superadmin déjà configuré.')

for realm in ['auditsphere', 'master']:
    settings = {'bruteForceProtected': True, 'permanentLockout': False, 'failureFactor': 5, 'waitIncrementSeconds': 30, 'minimumQuickLoginWaitSeconds': 30,
                'maxFailureWaitSeconds': 900, 'maxDeltaTimeSeconds': 43200, 'eventsEnabled': True, 'eventsExpiration': 604800,
                'adminEventsEnabled': True, 'adminEventsDetailsEnabled': False, 'sslRequired': 'all'}
    if realm == 'auditsphere':
        settings.update({'revokeRefreshToken': True, 'refreshTokenMaxReuse': 0, 'registrationAllowed': False})
    call('/admin/realms/' + realm, settings, 'PUT', token)
print('Protection contre la force brute, journal des événements et rotation des refresh tokens activés.')
