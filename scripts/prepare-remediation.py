#!/usr/bin/env python3
"""Create private local remediation database credentials without replacing existing values."""
from pathlib import Path
import os, secrets
root = Path(__file__).resolve().parents[1]
os.umask(0o077)
path = root / '.env'
values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
for key in ['REMEDIATION_DB_PASSWORD', 'REMEDIATION_ADMIN_PASSWORD', 'REMEDIATION_RUNTIME_PASSWORD', 'REMEDIATION_MIGRATION_PASSWORD']:
    if key not in values:
        with path.open('a') as output:
            output.write(key + '=' + secrets.token_urlsafe(32) + '\n')
print('Configuration privée des actions correctives prête.')
