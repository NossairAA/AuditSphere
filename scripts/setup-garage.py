#!/usr/bin/env python3
"""Prepare private Garage config and provision one read/write key per bucket: evidence and remediation."""
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
os.umask(0o077)
env_path = root / '.env'
if not env_path.exists():
    raise SystemExit('Exécuter scripts/init-local.py avant ce script.')
local = root / '.local'
local.mkdir(exist_ok=True)
config = local / 'garage.toml'
if not config.exists():
    rpc_secret = secrets.token_hex(32)
    config.write_text(f'''metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
db_engine = "sqlite"
replication_factor = 1
rpc_bind_addr = "0.0.0.0:3901"
rpc_public_addr = "127.0.0.1:3901"
rpc_secret = "{rpc_secret}"

[s3_api]
s3_region = "garage"
api_bind_addr = "0.0.0.0:3900"
''')

values = dict(line.split('=', 1) for line in env_path.read_text().splitlines() if '=' in line)
# Each store has its own endpoint. An external endpoint is provisioned where it runs, never from here.
stores = {
    'EVIDENCE': ('auditsphere-evidence', 'auditsphere-evidence'),
    'REMEDIATION': ('auditsphere-remediation', 'auditsphere-remediation'),
}
local_stores = [p for p in stores if values.get(p + '_S3_ENDPOINT', '') in ('', 'http://garage:3900', 'http://127.0.0.1:3900')]
for prefix in stores:
    if prefix not in local_stores:
        print(f'Stockage {prefix} externe configuré, provisionnement local ignoré.')
if not local_stores:
    raise SystemExit(0)

defaults = {}
for prefix in local_stores:
    defaults.update({
        prefix + '_S3_BUCKET': stores[prefix][0],
        prefix + '_S3_ACCESS_KEY': 'GK' + secrets.token_hex(16),
        prefix + '_S3_SECRET_KEY': secrets.token_hex(32),
    })
missing = {key: value for key, value in defaults.items() if key not in values}
if missing:
    with env_path.open('a') as output:
        for key, value in missing.items():
            output.write(f'{key}={value}\n')
    values.update(missing)

if '--configure-only' in sys.argv:
    print('Configuration privée Garage prête.')
    raise SystemExit(0)

def garage(*args):
    return subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'garage', '/garage', *args],
        cwd=root, text=True, capture_output=True,
    )

for attempt in range(30):
    if garage('status').returncode == 0: break
    time.sleep(1)
else:
    raise SystemExit('Garage indisponible. Démarrer docker compose up -d garage.')


def provision(bucket, key_id, secret, name):
    """One private bucket with its own key, never allowed on the other bucket."""
    if garage('bucket', 'info', bucket).returncode != 0:
        if garage('bucket', 'create', bucket).returncode:
            raise SystemExit('Création du bucket Garage échouée.')
    key = garage('key', 'info', '--show-secret', key_id)
    if key.returncode != 0:
        if garage('key', 'import', '--yes', '-n', name, key_id, secret).returncode:
            raise SystemExit('Import de la clé Garage échoué.')
    elif secret not in key.stdout:
        raise SystemExit('La clé Garage existante diffère du secret privé. Aucune rotation automatique.')
    if garage('bucket', 'allow', '--read', '--write', bucket, '--key', key_id).returncode:
        raise SystemExit('Attribution des droits Garage échouée.')


for prefix in local_stores:
    provision(values[prefix + '_S3_BUCKET'], values[prefix + '_S3_ACCESS_KEY'], values[prefix + '_S3_SECRET_KEY'], stores[prefix][1])
print('Garage local prêt pour ' + ', '.join(local_stores) + ' : bucket privé et clé limitée à la lecture et écriture de chacun.')
