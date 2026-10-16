#!/usr/bin/env python3
import subprocess,os
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=dict(l.split('=',1) for l in (root/'.env').read_text().splitlines() if '=' in l)
# Docker receives the secret through the process environment, not command arguments.
child=os.environ.copy();child['DB_PASSWORD']=env['AUDIT_MIGRATION_PASSWORD']
subprocess.run(['docker','compose','run','--rm','--no-deps','-e','MIGRATION_ONLY=true','-e','DB_USER=audit_migrator','-e','DB_PASSWORD','audit-service'],cwd=root,env=child,check=True)
