"""Validate deployment inputs without starting containers or displaying secrets."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import recovery

def validate(values):
    for name in ('CBS_APP_IMAGE','CADDY_IMAGE'):
        if not re.fullmatch(r'[a-zA-Z0-9._:/-]+@sha256:[0-9a-f]{64}',values.get(name,'')):
            raise recovery.OpsError(name+' must use an immutable sha256 image digest')
    domain=values.get('CBS_DOMAIN','')
    if not re.fullmatch(r'(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+',domain) or domain.endswith(('.test','.invalid','.example','.localhost')):
        raise recovery.OpsError('Supply the real public staging DNS name')
    if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',values.get('ACME_EMAIL','')):
        raise recovery.OpsError('Supply the certificate operations email')
    for name in ('CBS_RUNTIME_CONFIG','CBS_DATABASE_CA'):
        p=Path(values.get(name,''))
        if not p.is_absolute() or not p.is_file() or p.is_symlink():
            raise recovery.OpsError(name+' must be an absolute regular file')
    runtime=Path(values['CBS_RUNTIME_CONFIG'])
    if os.name!='nt' and (runtime.stat().st_mode & 0o077 or runtime.stat().st_uid!=10001):
        raise recovery.OpsError('Runtime secret file must be private and owned by container UID 10001')
    if 'REPLACE_' in runtime.read_text() or 'IDENTITY_DOMAIN' in runtime.read_text():
        raise recovery.OpsError('Replace runtime configuration placeholders')
    migration=Path(values.get('CBS_MIGRATION_DIRECTORY',''))
    if not migration.is_absolute() or not migration.is_dir() or migration.is_symlink() or not (migration/'plan.json').is_file():
        raise recovery.OpsError('Supply the separate migration directory and plan.json')
    if os.name!='nt' and (migration.stat().st_mode & 0o077 or migration.stat().st_uid!=10001):
        raise recovery.OpsError('Migration directory must be private and owned by container UID 10001')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker',default='docker');args=parser.parse_args()
    try:
        validate(os.environ)
        compose=Path(__file__).resolve().parents[1]/'deploy/staging/compose.yml'
        result=subprocess.run([args.docker,'compose','-f',str(compose),'config','--quiet'],capture_output=True,timeout=30)
        if result.returncode:raise recovery.OpsError('Compose configuration validation failed')
        print(json.dumps({'status':'PREFLIGHT_PASSED','deployed':False}));return 0
    except Exception as error:
        print(json.dumps({'status':'FAILED','reason':str(error) if isinstance(error,recovery.OpsError) else 'Check private staging configuration'}));return 1

if __name__=='__main__':raise SystemExit(main())
