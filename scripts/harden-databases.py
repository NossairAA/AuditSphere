#!/usr/bin/env python3
"""Idempotent local role migration, with private backups and no password output."""
import os, subprocess, secrets, datetime
from pathlib import Path
root=Path(__file__).resolve().parents[1]
os.chdir(root)
os.umask(0o077)
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
for key in ['AUDIT_ADMIN_PASSWORD','AUDIT_MIGRATION_PASSWORD','WEB_ADMIN_PASSWORD','AUDIT_RUNTIME_PASSWORD','WEB_RUNTIME_PASSWORD']:
 if key not in env:
  env[key]=secrets.token_urlsafe(32)
  with (root/'.env').open('a') as f:f.write(key+'='+env[key]+'\n')
def sql(service,user,database,query,check=True):
 result=subprocess.run(['docker','compose','exec','-T',service,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U',user,'-d',database],input=query,text=True,capture_output=True)
 if check and result.returncode:
  message=result.stderr
  for value in env.values():
   if len(value)>8:message=message.replace(value,'[redacted]')
  raise SystemExit(message)
 return result
backup=root/'.local'/'backups'/(datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+secrets.token_hex(3))
backup.mkdir(parents=True,exist_ok=True)
for db in ['audit','web','notification']:
 service=db+'-db';admin=db+'_admin';runtime=db+'_runtime';owner=db+'_migrator' if db!='web' else 'web_owner'
 current=admin if sql(service,admin,db,'SELECT 1;',False).returncode==0 else db
 # SQL uses psql quoting through format, values are generated URL-safe or validated identifiers.
 password=env[db.upper()+'_ADMIN_PASSWORD']
 assert all(c.isalnum() or c in '-_' for c in password)
 with (backup/(db+'.dump')).open('wb') as f:
  subprocess.run(['docker','compose','exec','-T',service,'pg_dump','-U',current,'-d',db,'-Fc'],stdout=f,check=True)
 sql(service,current,db,f"SELECT format('CREATE ROLE {admin} LOGIN SUPERUSER PASSWORD %L','{password}') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='{admin}')\\gexec\n")
 runtimepass=env[db.upper()+'_RUNTIME_PASSWORD'];assert all(c.isalnum() or c in '-_' for c in runtimepass)
 sql(service,admin,db,f"SELECT 'CREATE ROLE {runtime} LOGIN' WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='{runtime}')\\gexec\nSELECT 'CREATE ROLE {owner} NOLOGIN' WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='{owner}')\\gexec\n")
 if db!='web':
  migrationpass=env[db.upper()+'_MIGRATION_PASSWORD'];assert all(c.isalnum() or c in '-_' for c in migrationpass)
  sql(service,admin,db,f"ALTER ROLE {owner} LOGIN PASSWORD '{migrationpass}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n")
 sql(service,admin,db,f"SELECT format('ALTER TABLE public.%I OWNER TO {owner}',tablename) FROM pg_tables WHERE schemaname='public'\\gexec\n\nALTER DATABASE {db} OWNER TO {owner};\nALTER SCHEMA public OWNER TO {owner};\nREVOKE CREATE ON SCHEMA public FROM PUBLIC;\nREVOKE ALL ON DATABASE {db} FROM PUBLIC;\nGRANT CONNECT ON DATABASE {db} TO {runtime};\nALTER ROLE {runtime} PASSWORD '{runtimepass}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\nGRANT USAGE ON SCHEMA public TO {runtime};\nALTER ROLE {runtime} SET statement_timeout='5s';\nALTER ROLE {runtime} SET lock_timeout='2s';\nREVOKE ALL ON ALL TABLES IN SCHEMA public FROM {runtime};\n")
 if db=='web':
  schema=(root/'apps/web/db/001-sessions.sql').read_text()
  sql(service,admin,db,'SET ROLE web_owner;\n'+schema+'\nRESET ROLE;\nGRANT SELECT,INSERT,UPDATE,DELETE ON web_sessions TO web_runtime;\n')
 elif db=='audit':
  sql(service,admin,db,"SELECT format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO audit_runtime',tablename) FROM pg_tables WHERE schemaname='public' AND tablename IN ('missions','mission_assignments','assignment_outbox')\\gexec\n")
 else:
  sql(service,admin,db,"SELECT 'GRANT SELECT,INSERT ON notifications TO notification_runtime' WHERE EXISTS(SELECT FROM pg_tables WHERE schemaname='public' AND tablename='notifications')\\gexec\n")
  sql(service,admin,db,"SELECT 'GRANT UPDATE(read_at) ON notifications TO notification_runtime' WHERE EXISTS(SELECT FROM information_schema.columns WHERE table_schema='public' AND table_name='notifications' AND column_name='read_at')\\gexec\n")
 print(db+': runtime sans privilèges administrateur, sauvegarde privée créée.')
print('Sauvegardes : '+str(backup.relative_to(root)))

web=root/'apps/web/.env.local'
if web.exists():
 values=dict(l.split('=',1) for l in web.read_text().splitlines() if '=' in l)
 values['SESSION_DATABASE_URL']='postgresql://web_runtime:'+env['WEB_RUNTIME_PASSWORD']+'@localhost:5544/web'
 web.write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
