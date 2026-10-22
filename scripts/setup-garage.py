#!/usr/bin/env python3
"""Prepare private Garage config and provision one read/write evidence key."""
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
if values.get('EVIDENCE_S3_ENDPOINT', '') not in ('', 'http://garage:3900', 'http://127.0.0.1:3900'):
    print('Stockage Garage externe configuré, provisionnement local ignoré.')
    raise SystemExit(0)

defaults = {
    'EVIDENCE_S3_BUCKET': 'auditsphere-evidence',
    'EVIDENCE_S3_ACCESS_KEY': 'GK' + secrets.token_hex(16),
    'EVIDENCE_S3_SECRET_KEY': secrets.token_hex(32),
}
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

bucket = values['EVIDENCE_S3_BUCKET']
key_id = values['EVIDENCE_S3_ACCESS_KEY']
secret = values['EVIDENCE_S3_SECRET_KEY']
for attempt in range(30):
    if garage('status').returncode == 0: break
    time.sleep(1)
else:
    raise SystemExit('Garage indisponible. Démarrer docker compose up -d garage.')
if garage('bucket', 'info', bucket).returncode != 0:
    result = garage('bucket', 'create', bucket)
    if result.returncode:
        raise SystemExit('Création du bucket Garage échouée.')
key = garage('key', 'info', '--show-secret', key_id)
if key.returncode != 0:
    result = garage('key', 'import', '--yes', '-n', 'auditsphere-evidence', key_id, secret)
    if result.returncode:
        raise SystemExit('Import de la clé Garage échoué.')
elif secret not in key.stdout:
    raise SystemExit('La clé Garage existante diffère du secret privé. Aucune rotation automatique.')
result = garage('bucket', 'allow', '--read', '--write', bucket, '--key', key_id)
if result.returncode:
    raise SystemExit('Attribution des droits Garage échouée.')
print('Garage prêt, bucket privé et clé limitée à la lecture et écriture.')
