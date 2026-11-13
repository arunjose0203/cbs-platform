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
    validate_identity(values,runtime.read_text())

DOMAIN=r'(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+'
IDENTITY_DB=re.compile(r'jdbc:postgresql://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9_]+\?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/db-ca\.crt')
FORBIDDEN_IDENTITY=('KC_HOSTNAME_STRICT','KC_HTTPS_CERTIFICATE_FILE','KC_DB_URL_PROPERTIES','KC_HOSTNAME_BACKCHANNEL_DYNAMIC','KC_FEATURES')

def env_file(path):
    """Read KEY=VALUE lines without expanding anything; values are never echoed."""
    values={}
    for number,line in enumerate(Path(path).read_text().splitlines(),1):
        line=line.strip()
        if not line or line.startswith('#'):continue
        if '=' not in line:raise recovery.OpsError(f'Identity environment line {number} is not KEY=VALUE')
        key,value=line.split('=',1);values[key.strip()]=value.strip()
    return values

def validate_identity(values,runtime_text):
    """P-03: Keycloak on PostgreSQL behind the edge, and CBS trusting exactly that realm."""
    if not re.fullmatch(r'[a-zA-Z0-9._:/-]+@sha256:[0-9a-f]{64}',values.get('KEYCLOAK_IMAGE','')):
        raise recovery.OpsError('KEYCLOAK_IMAGE must use an immutable sha256 image digest')
    domain=values.get('IDENTITY_DOMAIN','')
    if not re.fullmatch(DOMAIN,domain) or domain.endswith(('.test','.invalid','.example','.localhost')):
        raise recovery.OpsError('Supply the real public identity DNS name')
    if domain==values.get('CBS_DOMAIN'):
        raise recovery.OpsError('IDENTITY_DOMAIN must differ from CBS_DOMAIN so the portal and sign-in keep separate origins')
    p=Path(values.get('CBS_IDENTITY_ENV',''))
    if not p.is_absolute() or not p.is_file() or p.is_symlink():
        raise recovery.OpsError('CBS_IDENTITY_ENV must be an absolute regular file')
    if os.name!='nt' and p.stat().st_mode & 0o077:
        raise recovery.OpsError('Identity environment file must not be readable by group or other users')
    identity=env_file(p)
    if not IDENTITY_DB.fullmatch(identity.get('KC_DB_URL','')):
        raise recovery.OpsError('KC_DB_URL must be a direct PostgreSQL URL with sslmode=verify-full&sslrootcert=/opt/keycloak/conf/db-ca.crt')
    if not identity.get('KC_DB_USERNAME') or not identity.get('KC_DB_PASSWORD'):
        raise recovery.OpsError('Identity database username and password are required')
    if any('REPLACE_' in v for v in identity.values()):
        raise recovery.OpsError('Replace identity environment placeholders')
    if identity['KC_DB_USERNAME'] in re.findall(r'username:\s*(\S+)',runtime_text):
        raise recovery.OpsError('Keycloak needs its own database login, separate from the CBS runtime logins')
    present=[k for k in FORBIDDEN_IDENTITY if k in identity]
    if present:
        raise recovery.OpsError('Remove identity overrides that weaken the reviewed configuration: '+', '.join(present))
    issuer=f'https://{domain}/realms/cbs'
    expected={'issuer':issuer,'jwk-set-uri':issuer+'/protocol/openid-connect/certs','client-id':'cbs-portal',
              'redirect-uri':f'https://{values.get("CBS_DOMAIN","")}/admin/index.html'}
    for key,value in expected.items():
        found=re.findall(r'^\s*'+re.escape(key)+r':\s*(\S+)\s*$',runtime_text,re.M)
        if found!=[value]:
            raise recovery.OpsError(f'Runtime cbs.*.{key} must be {value}')

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
