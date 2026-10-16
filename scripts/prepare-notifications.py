#!/usr/bin/env python3
"""Create private local notification database credentials without replacing existing values."""
from pathlib import Path
import os, secrets
root = Path(__file__).resolve().parents[1]
os.umask(0o077)
path = root / '.env'
values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
for key in ['NOTIFICATION_DB_PASSWORD', 'NOTIFICATION_ADMIN_PASSWORD', 'NOTIFICATION_RUNTIME_PASSWORD', 'NOTIFICATION_MIGRATION_PASSWORD']:
    if key not in values:
        with path.open('a') as output:
            output.write(key + '=' + secrets.token_urlsafe(32) + '\n')
print('Configuration privée des notifications prête.')
