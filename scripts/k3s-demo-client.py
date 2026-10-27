#!/usr/bin/env python3
"""Switch the password-grant client auditsphere-demo of the k3s Keycloak (phase 10, ADR-0032).

The client stays disabled. scripts/k3s-check.sh enables it for one run and disables it on exit.
  status  : print enabled or disabled
  enable  : refuse if already enabled, a previous run may not have closed it, then enable
  disable : disable, idempotent
Uses the k3s-demo-switch service account, limited to the clients of the auditsphere realm. Nothing secret is printed.
"""
from pathlib import Path
import base64, json, os, subprocess, sys, urllib.parse, urllib.request

root = Path(__file__).resolve().parents[1]
os.environ.setdefault('KUBECONFIG', str(Path.home() / '.kube' / 'auditsphere.yaml'))
local = dict(line.split('=', 1) for line in (root / '.local' / 'k3s.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
identity_origin = local['OIDC_ORIGIN']
CLIENT = 'auditsphere-demo'


def switch_secret():
    result = subprocess.run(['kubectl', '-n', 'auditsphere', 'get', 'secret', 'keycloak-demo-switch', '-o', 'jsonpath={.data.client_secret}'],
                            text=True, capture_output=True)
    if result.returncode or not result.stdout:
        raise SystemExit('Secret keycloak-demo-switch absent. Lancer scripts/k3s-setup-keycloak.py.')
    return base64.b64decode(result.stdout).decode()


def call(path, data=None, method=None, token=None, form=False):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    payload = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(identity_origin + path, data=payload, headers=headers, method=method), timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


action = sys.argv[1] if len(sys.argv) == 2 else ''
if action not in ('status', 'enable', 'disable'):
    raise SystemExit('Usage : k3s-demo-client.py status|enable|disable')
token = call('/realms/auditsphere/protocol/openid-connect/token',
             {'grant_type': 'client_credentials', 'client_id': 'k3s-demo-switch', 'client_secret': switch_secret()}, form=True)['access_token']
base = '/admin/realms/auditsphere/clients'
client = call(base + '?clientId=' + CLIENT, token=token)[0]
if action == 'status':
    print('enabled' if client['enabled'] else 'disabled')
elif action == 'enable' and client['enabled']:
    raise SystemExit('Client ' + CLIENT + ' déjà actif : une exécution précédente ne l’a pas refermé, ou une autre tourne. '
                     'Vérifier, puis lancer scripts/k3s-demo-client.py disable.')
else:
    client['enabled'] = action == 'enable'
    call(base + '/' + client['id'], client, 'PUT', token)
    print('Client ' + CLIENT + (' activé.' if client['enabled'] else ' désactivé.'))
