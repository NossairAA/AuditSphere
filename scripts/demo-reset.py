#!/usr/bin/env python3
"""Nightly reset of the demo profile (ADR-0031), run by the demo-reset CronJob of k3s.

  accounts : restore the passwords, names and email of the demo accounts. Remove OTP, WebAuthn and recovery codes,
             required actions, sessions and brute-force lockouts. superadmin is never touched.
  seed     : delete the evidence and attachment objects listed by the database step, then reseed the 40 missions of
             seed-demo-missions.py, with the auditsphere-demo client enabled for this step only (ADR-0032).

The Temporal and database steps run before seed in their own containers, see infra/helm/demo-reset.
Nothing secret is printed. Values come from the environment and from the demo-accounts secret.
"""
from pathlib import Path
import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
import demo_accounts

KEYCLOAK = os.environ.get('KEYCLOAK_URL', 'http://keycloak.auditsphere.svc.cluster.local:8080')
REALM = KEYCLOAK + '/realms/auditsphere'
ADMIN = KEYCLOAK + '/admin/realms/auditsphere'
ACCOUNTS = Path(os.environ.get('DEMO_ACCOUNTS_FILE', '/etc/auditsphere/demo/accounts.env'))
WORK = Path(os.environ.get('DEMO_RESET_WORK', '/work'))


def call(url, data=None, method=None, token=None, form=False):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    payload = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, data=payload, headers=headers, method=method), timeout=20) as response:
        body = response.read()
        return json.loads(body) if body else None


def wait_keycloak():
    # After a restart of k3s, the CronJob catches up a missed run while Keycloak is still starting: wait at most 5 min.
    for _ in range(60):
        try:
            call(REALM)
            return
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(5)
    raise SystemExit('Keycloak injoignable après 5 minutes.')


def service_token(client_id, secret_variable):
    form = {'grant_type': 'client_credentials', 'client_id': client_id, 'client_secret': os.environ[secret_variable]}
    return call(REALM + '/protocol/openid-connect/token', form, form=True)['access_token']


def passwords():
    values = dict(line.split('=', 1) for line in ACCOUNTS.read_text().splitlines() if '=' in line)
    return {name: values[name.upper() + '_PASSWORD'] for name in demo_accounts.PROFILES}


def reset_accounts():
    wait_keycloak()
    token = service_token('demo-reset', 'DEMO_RESET_CLIENT_SECRET')
    secrets = passwords()
    for username, (first, last, _roles) in demo_accounts.PROFILES.items():
        users = call(ADMIN + '/users?username=' + username + '&exact=true', token=token)
        # Same guard as k3s-setup-keycloak.py: never reset an account that is not the synthetic one.
        if not users or users[0].get('email') != demo_accounts.email(username):
            raise SystemExit('Compte ' + username + ' absent ou avec une autre identité. Lancer scripts/k3s-setup-keycloak.py.')
        user = ADMIN + '/users/' + users[0]['id']
        call(user, {'firstName': first, 'lastName': last, 'email': demo_accounts.email(username), 'emailVerified': True,
                    'enabled': True, 'requiredActions': []}, 'PUT', token)
        call(user + '/reset-password', {'type': 'password', 'value': secrets[username], 'temporary': False}, 'PUT', token)
        # OTP, WebAuthn and recovery codes added by a visitor would lock the next one out of the password sign-in.
        for credential in call(user + '/credentials', token=token):
            if credential['type'] != 'password':
                call(user + '/credentials/' + credential['id'], method='DELETE', token=token)
        call(user + '/logout', method='POST', token=token)
        call(KEYCLOAK + '/admin/realms/auditsphere/attack-detection/brute-force/users/' + users[0]['id'], method='DELETE', token=token)
    print('Comptes de démonstration rétablis : ' + ', '.join(demo_accounts.PROFILES) + '.')


def switch(enabled):
    token = service_token('k3s-demo-switch', 'DEMO_SWITCH_CLIENT_SECRET')
    client = call(ADMIN + '/clients?clientId=auditsphere-demo', token=token)[0]
    # Same rule as scripts/k3s-demo-client.py: an enabled client means another run is in progress or was not closed.
    if enabled and client['enabled']:
        raise SystemExit('Client auditsphere-demo déjà actif : une autre exécution est en cours ou ne l’a pas refermé.')
    client['enabled'] = enabled
    call(ADMIN + '/clients/' + client['id'], client, 'PUT', token)


def seed():
    for name, prefix, label in [('evidence-keys.txt', 'EVIDENCE', 'preuves'), ('attachment-keys.txt', 'REMEDIATION', 'justificatifs')]:
        keys = WORK / name
        if keys.exists() and keys.read_text().strip():
            from evidence_storage import request
            listed = keys.read_text().split()
            for key in listed:
                try:
                    request('DELETE', key, prefix=prefix)
                except urllib.error.HTTPError as error:
                    if error.code != 404:
                        raise
            print(str(len(listed)) + ' ' + label + ' retirés du stockage.', flush=True)
    switch(True)
    try:
        # The seed sets the mode of its manifest folder: a folder it creates, not the root of the emptyDir volume.
        environment = {**os.environ, 'SEED_ACCOUNTS_FILE': str(ACCOUNTS), 'SEED_MANIFEST': str(WORK / 'seed' / 'demo-missions-manifest.json'),
                       'SEED_TOKEN_URL': REALM + '/protocol/openid-connect/token'}
        subprocess.run([sys.executable, str(Path(__file__).with_name('seed-demo-missions.py')), '--apply'], env=environment, check=True)
    finally:
        switch(False)


if __name__ == '__main__':
    action = sys.argv[1] if len(sys.argv) == 2 else ''
    if action == 'accounts':
        reset_accounts()
    elif action == 'seed':
        seed()
    else:
        raise SystemExit('Usage : demo-reset.py accounts|seed')
