"""CBS cold-backup and isolated-restore tooling. No shell commands or credentials in logs."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
from contextlib import ExitStack
from datetime import datetime, timezone
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b'CBSBACK1'
IDENT = re.compile(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}\Z')
SAFE = re.compile(r'[a-z][a-z0-9_-]{0,62}\Z')

class OpsError(Exception):
    pass

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()

def secret(path):
    p = Path(path)
    if not p.is_file() or p.is_symlink():
        raise OpsError('Secret file is missing or is a symbolic link')
    if os.name != 'nt' and p.stat().st_mode & 0o077:
        raise OpsError('Secret files must not be accessible to group or other users')
    return p.read_bytes()

def keys(config):
    raw = secret(config['key_file'])
    if len(raw) != 32:
        raise OpsError('Backup key must contain exactly 32 random bytes')
    return (hmac.digest(raw, b'CBS encryption v1', 'sha256'),
            hmac.digest(raw, b'CBS manifest v1', 'sha256'))

def private_write(path, data):
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())

def digest(path):
    with open(path, 'rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def encrypt(source, path, key):
    nonce = os.urandom(12)
    crypt = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    crypt.authenticate_additional_data(MAGIC)
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
        output.write(MAGIC + nonce)
        while chunk := source.read(1024 * 1024):
            output.write(crypt.update(chunk))
        output.write(crypt.finalize() + crypt.tag)
        output.flush()
        os.fsync(output.fileno())

def decrypt(path, output, key):
    # Authentication is completed before callers execute pg_restore or expose a file.
    size = Path(path).stat().st_size
    if size < 36:
        raise OpsError('Truncated encrypted archive')
    with open(path, 'rb') as source:
        if source.read(8) != MAGIC:
            raise OpsError('Unknown backup format')
        nonce = source.read(12)
        source.seek(-16, 2)
        tag = source.read(16)
        source.seek(20)
        crypt = Cipher(algorithms.AES(key), modes.GCM(nonce, tag)).decryptor()
        crypt.authenticate_additional_data(MAGIC)
        left = size - 36
        while left:
            chunk = source.read(min(left, 1024 * 1024))
            if not chunk:
                raise OpsError('Truncated encrypted archive')
            left -= len(chunk)
            output.write(crypt.update(chunk))
        output.write(crypt.finalize())
        output.flush()

def validate(config):
    databases = config.get('databases', [])
    if not databases or len({d['name'] for d in databases}) != len(databases):
        raise OpsError('Supply a unique complete database inventory')
    endpoints = set()
    for d in databases:
        if not SAFE.fullmatch(d['name']) or not IDENT.fullmatch(d['database']) or not IDENT.fullmatch(d['user']):
            raise OpsError('Invalid inventory identifier')
        if not re.fullmatch(r'[a-zA-Z0-9.-]+', d['host']) or not 1 <= int(d['port']) <= 65535:
            raise OpsError('Invalid database endpoint')
        endpoint = (d['host'], int(d['port']), d['database'])
        if endpoint in endpoints or '-pooler' in d['host']:
            raise OpsError('Use distinct databases and direct PostgreSQL endpoints, not transaction poolers')
        endpoints.add(endpoint)
        if d['kind'] not in ('control', 'bank', 'identity'):
            raise OpsError('Unknown database inventory kind')
        if config.get('local_test') and d['host'] not in ('localhost', '127.0.0.1'):
            raise OpsError('Local test mode is restricted to loopback')
        if d['kind'] == 'bank' and not re.fullmatch(r'[0-9a-f-]{36}', d.get('tenant_id', '')):
            raise OpsError('Each bank requires its expected tenant UUID')
    if sum(d['kind'] == 'control' for d in databases) != 1:
        raise OpsError('Exactly one control database is required')
    if not any(d['kind'] == 'identity' for d in databases):
        raise OpsError('Identity database is required for a complete self-hosted recovery set')
    for item in config.get('files', []):
        if not SAFE.fullmatch(item['name']):
            raise OpsError('Invalid configuration artifact name')
    if not config.get('files'):
        raise OpsError('Include identity/deployment configuration artifacts')
    secret(config['pgpass_file'])
    if not config.get('local_test'):
        if not Path(config.get('sslrootcert', '')).is_file():
            raise OpsError('A trusted PostgreSQL CA file is required')
    keys(config)

def tool(config, name):
    value = Path(config['pg_bin']) / (name + ('.exe' if os.name == 'nt' else ''))
    if not value.is_file():
        raise OpsError('Required PostgreSQL client tool is unavailable: ' + name)
    return str(value)

def environment(config):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith('PG'):
            del env[key]
    env.update(PGPASSFILE=str(Path(config['pgpass_file']).resolve()), PGCONNECT_TIMEOUT='10',
               PGSSLMODE='disable' if config.get('local_test') else 'verify-full',
               PGAPPNAME='cbs-operations', PGOPTIONS='-c timezone=UTC -c datestyle=ISO,MDY')
    if not config.get('local_test'):
        env['PGSSLROOTCERT'] = str(Path(config['sslrootcert']).resolve())
    return env

def connection(d):
    return ['--host', d['host'], '--port', str(d['port']), '--username', d['user'], '--dbname', d['database'], '--no-password']

def query(config, d, sql):
    result = subprocess.run([tool(config, 'psql'), *connection(d), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql],
                            env=environment(config), capture_output=True, timeout=config.get('query_timeout_seconds', 120))
    if result.returncode:
        raise OpsError('Database check failed for ' + d['name'])
    return result.stdout.decode('utf-8').strip()

def qi(value):
    return '"' + value.replace('"', '""') + '"'

def tables(config, d):
    return json.loads(query(config, d, "SELECT coalesce(json_agg(json_build_array(n.nspname,c.relname) ORDER BY n.nspname,c.relname),'[]') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p') AND n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'"))

class Fence:
    """Hold SHARE table locks for the whole recovery set until all dumps finish."""
    def __init__(self, config, d):
        self.config, self.d, self.process = config, d, None
    def __enter__(self):
        names = tables(self.config, self.d)
        if not names:
            raise OpsError('Source database is empty: ' + self.d['name'])
        sql = "BEGIN; SET lock_timeout='10s'; SET idle_in_transaction_session_timeout='0';\n"
        sql += ''.join('LOCK TABLE ' + qi(s) + '.' + qi(t) + ' IN SHARE MODE;\n' for s, t in names)
        sql += "SELECT 'CBS_LOCKED:'||pg_backend_pid();\n"
        self.process = subprocess.Popen([tool(self.config, 'psql'), *connection(self.d), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
            env=environment(self.config), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.process.stdin.write(sql.encode()); self.process.stdin.flush()
        result = queue.Queue()
        def observe():
            for line in self.process.stdout:
                if line.startswith(b'CBS_LOCKED:'):
                    result.put(line); return
            result.put(None)
        threading.Thread(target=observe, daemon=True).start()
        try:
            line = result.get(timeout=30)
            if not line:
                raise OpsError('Could not acquire maintenance fence')
            self.pid = int(line.split(b':')[1])
            # Stop applications, workers, identity writers AND pools before cold backup.
            if int(query(self.config, self.d, f"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid NOT IN (pg_backend_pid(),{self.pid})")):
                raise OpsError('Other database sessions remain; stop writers and connection pools before backup')
            return self
        except Exception:
            self.__exit__(None, None, None); raise
    def __exit__(self, *args):
        if self.process:
            if self.process.poll() is None:
                try:
                    self.process.stdin.write(b'ROLLBACK;\n\\q\n'); self.process.stdin.flush()
                    self.process.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired):
                    self.process.kill(); self.process.wait()
            self.process.stdin.close(); self.process.stdout.close()

def fingerprint(config, d):
    output = {}
    for schema, table in tables(config, d):
        sql = f'SELECT md5(row_to_json(t)::text) FROM {qi(schema)}.{qi(table)} t ORDER BY 1'
        process = subprocess.Popen([tool(config, 'psql'), *connection(d), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql],
            env=environment(config), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        watchdog = threading.Timer(config.get('operation_timeout_seconds', 3600), process.kill)
        watchdog.daemon = True; watchdog.start()
        hasher, count = hashlib.sha256(), 0
        for line in process.stdout:
            hasher.update(line.strip() + b'\n'); count += 1
        process.stdout.close()
        code = process.wait(timeout=30); watchdog.cancel()
        if code:
            raise OpsError('Fingerprint failed for ' + d['name'])
        output[schema + '.' + table] = {'rows': count, 'sha256': hasher.hexdigest()}
    sequences = json.loads(query(config, d, "SELECT coalesce(json_agg(json_build_array(n.nspname,c.relname) ORDER BY n.nspname,c.relname),'[]') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='S' AND n.nspname NOT LIKE 'pg_%'"))
    for schema, name in sequences:
        output[schema+'.'+name] = {'sequence': query(config, d, f'SELECT last_value,is_called FROM {qi(schema)}.{qi(name)}')}
    return output

def check_inventory(config):
    control = next(d for d in config['databases'] if d['kind'] == 'control')
    required = json.loads(query(config, control, "SELECT coalesce(json_agg(tenant_id::text ORDER BY tenant_id),'[]') FROM platform.database_binding b JOIN platform.tenant t ON t.id=b.tenant_id WHERE b.schema_version IS NOT NULL OR t.status IN ('ACTIVE','SUSPENDED')"))
    if query(config, control, "SELECT count(*) FROM platform.tenant WHERE status IN ('PROVISIONING','PROVISIONING_FAILED')") != '0':
        raise OpsError('Resolve incomplete provisioning before creating a coordinated recovery set')
    actual = sorted(d['tenant_id'] for d in config['databases'] if d['kind'] == 'bank')
    if sorted(required) != actual:
        raise OpsError('Bank inventory does not match active control-database bindings')
    for d in config['databases']:
        if d['kind'] == 'bank' and query(config, d, 'SELECT tenant_id FROM cbs_identity.owner WHERE singleton') != d['tenant_id']:
            raise OpsError('Bank ownership mismatch')

def financial_checks(config):
    control = next(d for d in config['databases'] if d['kind'] == 'control')
    result = {}
    for d in config['databases']:
        if d['kind'] != 'bank':
            continue
        checks = {}
        if query(config, d, "SELECT to_regclass('bank.journal') IS NOT NULL") == 't':
            invalid = query(config, d, "SELECT count(*) FROM (SELECT j.id FROM bank.journal j LEFT JOIN bank.journal_line l ON l.journal_id=j.id GROUP BY j.id HAVING count(l.journal_id)<2 OR sum(CASE WHEN l.side='DEBIT' THEN l.amount ELSE -l.amount END)<>0) q")
            if invalid != '0':
                raise OpsError('Unbalanced bank journals')
            invalid = query(config, d, "SELECT count(*) FROM bank.ledger_balance b FULL JOIN (SELECT ledger_id,sum(CASE WHEN side='DEBIT' THEN amount ELSE 0 END) debit,sum(CASE WHEN side='CREDIT' THEN amount ELSE 0 END) credit FROM bank.journal_line GROUP BY ledger_id) l USING(ledger_id) WHERE coalesce(b.debit,0)<>coalesce(l.debit,0) OR coalesce(b.credit,0)<>coalesce(l.credit,0)")
            if invalid != '0':
                raise OpsError('Ledger balances do not reconcile to journal lines')
            checks['journals_balanced'] = True
            checks['ledger_balances_reconciled'] = True
            # UUID parsed by validation; cast instead of accepting arbitrary SQL fragments.
            deliveries = json.loads(query(config, control, "SELECT coalesce(json_agg(json_build_object('id',request_id,'completed',completed)),'[]') FROM platform.journal_delivery WHERE tenant_id='" + str(__import__('uuid').UUID(d['tenant_id'])) + "'::uuid"))
            posted = set(query(config, d, 'SELECT id FROM bank.journal').splitlines())
            if any(x['completed'] and x['id'] not in posted for x in deliveries):
                raise OpsError('Control plane marks a missing bank journal as completed')
            checks['pending_postings'] = sum(not x['completed'] for x in deliveries)
            checks['pending_already_posted'] = sum(not x['completed'] and x['id'] in posted for x in deliveries)
        if query(config, d, "SELECT to_regclass('bank.customer_position') IS NOT NULL") == 't':
            if query(config, d, "SELECT count(*) FROM bank.customer_position p LEFT JOIN bank.ledger_balance b ON b.ledger_id=p.ledger_id WHERE p.posted<>coalesce(b.credit-b.debit,0) OR p.posted<0 OR p.held<0 OR p.held>p.posted") != '0':
                raise OpsError('Customer balances do not reconcile')
            checks['customer_balances_reconciled'] = True
        result[d['name']] = checks
    return result

def backup(config, destination):
    validate(config)
    encryption, signing = keys(config)
    root = Path(destination).resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    started = time.time()
    manifest = {'format': 1, 'started_at': datetime.now(timezone.utc).isoformat(), 'databases': [], 'files': []}
    with ExitStack() as stack:
        fences = [stack.enter_context(Fence(config, d)) for d in config['databases']]
        check_inventory(config)
        manifest['financial_checks'] = financial_checks(config)
        for d, fence in zip(config['databases'], fences):
            before = fingerprint(config, d)
            filename = d['name'] + '.dump.enc'
            process = subprocess.Popen([tool(config, 'pg_dump'), *connection(d), '--format=custom', '--no-owner', '--no-privileges'],
                env=environment(config), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            watchdog = threading.Timer(config.get('operation_timeout_seconds', 3600), process.kill)
            watchdog.daemon = True; watchdog.start()
            try:
                encrypt(process.stdout, root / filename, encryption)
                if process.wait(timeout=30):
                    raise OpsError('Database dump failed for ' + d['name'])
            finally:
                watchdog.cancel()
                process.stdout.close()
                if process.poll() is None:
                    process.kill(); process.wait()
            if before != fingerprint(config, d) or fence.process.poll() is not None:
                raise OpsError('Source changed or maintenance fence was lost')
            manifest['databases'].append({'name': d['name'], 'kind': d['kind'], 'tenant_id': d.get('tenant_id'),
                'source': [d['host'], int(d['port']), d['database']], 'file': filename,
                'sha256': digest(root / filename), 'fingerprint': before})
        for d, fence in zip(config['databases'], fences):
            if query(config, d, f"SELECT count(*) FROM pg_stat_activity WHERE pid={fence.pid} AND xact_start IS NOT NULL") != '1':
                raise OpsError('Maintenance fence was lost; backup is incomplete')
        for index, item in enumerate(config['files']):
            path = Path(item['path'])
            if not path.is_file() or path.is_symlink():
                raise OpsError('Configuration artifact must be a regular file')
            filename = f'config-{index}.enc'
            with path.open('rb') as source:
                encrypt(source, root / filename, encryption)
            manifest['files'].append({'name': item['name'], 'file': filename, 'sha256': digest(root / filename)})
    manifest['completed_at'] = datetime.now(timezone.utc).isoformat()
    manifest['duration_seconds'] = round(time.time() - started, 3)
    envelope = {'manifest': manifest, 'hmac': hmac.digest(signing, canonical(manifest), 'sha256').hex()}
    private_write(root / 'manifest.json', canonical(envelope))
    return {'status': 'BACKUP_COMPLETE', 'directory': str(root), 'databases': len(manifest['databases'])}

def authenticated_manifest(config, directory, verify_files=True):
    root = Path(directory).resolve()
    envelope = json.loads((root / 'manifest.json').read_text())
    encryption, signing = keys(config)
    m = envelope['manifest']
    if not hmac.compare_digest(envelope['hmac'], hmac.digest(signing, canonical(m), 'sha256').hex()) or m.get('format') != 1:
        raise OpsError('Manifest authentication failed')
    for item in m['databases'] + m['files']:
        if Path(item['file']).name != item['file'] or '\\' in item['file'] or (root / item['file']).is_symlink():
            raise OpsError('Unsafe archive path')
        if verify_files and digest(root / item['file']) != item['sha256']:
            raise OpsError('Encrypted archive checksum failed')
    return m, encryption

def restore(config, directory, target_config, report_path):
    validate(config); validate(target_config)
    manifest, encryption = authenticated_manifest(config, directory)
    targets = {d['name']: d for d in target_config['databases']}
    if set(targets) != {d['name'] for d in manifest['databases']}:
        raise OpsError('Restore inventory must match the complete backup set')
    # Check ALL targets before writing any database. Never overwrite a live or non-empty database.
    for item in manifest['databases']:
        d = targets[item['name']]
        if not d['database'].startswith('cbs_restore_') or d['kind'] != item['kind'] or d.get('tenant_id') != item.get('tenant_id'):
            raise OpsError('Restore targets must use cbs_restore_ names and matching identities')
        if [d['host'], int(d['port']), d['database']] == item['source'] or tables(target_config, d) or query(target_config,d,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'") != '0':
            raise OpsError('Restore target is not isolated and empty')
        if query(target_config,d,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()") != '0':
            raise OpsError('Restore target has connected clients')
    if Path(report_path).exists():
        raise OpsError('Choose a new restore report path')
    recovered_files = target_config.get('recovered_files_directory')
    if recovered_files and Path(recovered_files).exists():
        raise OpsError('Recovered configuration directory must be new')
    started, verified = time.time(), []
    temp_parent = Path(target_config['scratch_dir']).resolve()
    temp_parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='cbs-restore-', dir=temp_parent) as scratch:
        # Authenticate every archive before any restoration starts.
        for item in manifest['databases'] + manifest['files']:
            with open(os.open(Path(scratch) / item['file'], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
                decrypt(Path(directory) / item['file'], output, encryption)
        for item in manifest['databases']:
            d = targets[item['name']]
            result = subprocess.run([tool(target_config, 'pg_restore'), *connection(d), '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges', str(Path(scratch) / item['file'])],
                env=environment(target_config), capture_output=True, timeout=target_config.get('restore_timeout_seconds', 3600))
            if result.returncode:
                raise OpsError('Restore failed; keep all targets isolated and do not start CBS')
            if fingerprint(target_config, d) != item['fingerprint']:
                raise OpsError('Restored table/sequence fingerprints do not match')
            verified.append(d['name'])
        check_inventory(target_config)
        restored_checks = financial_checks(target_config)
        if restored_checks != manifest['financial_checks']:
            raise OpsError('Financial recovery checks do not match the source')
        # Export only after the complete restore verifies; never install into a running identity provider.
        if recovered_files:
            output_directory = Path(recovered_files)
            output_directory.mkdir(mode=0o700, parents=True, exist_ok=False)
            for index, item in enumerate(manifest['files']):
                if not SAFE.fullmatch(item['name']):
                    raise OpsError('Unsafe configuration artifact name')
                private_write(output_directory / (str(index)+'-'+item['name']), (Path(scratch)/item['file']).read_bytes())
    report = {'status': 'RESTORE_VERIFIED', 'verified_databases': verified, 'configuration_artifacts': [i['name'] for i in manifest['files']],
              'backup_completed_at': manifest['completed_at'], 'completed_at': datetime.now(timezone.utc).isoformat(),
              'restore_seconds': round(time.time() - started, 3), 'financial_checks': restored_checks, 'bank_acceptance': 'REQUIRED', 'promoted': False}
    signing = keys(config)[1]
    private_write(Path(report_path), canonical({'report': report, 'hmac': hmac.digest(signing, canonical(report), 'sha256').hex()}))
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['backup', 'restore', 'keygen'])
    parser.add_argument('--config'); parser.add_argument('--directory'); parser.add_argument('--targets'); parser.add_argument('--report')
    args = parser.parse_args()
    try:
        if args.command == 'keygen':
            private_write(Path(args.directory), os.urandom(32)); result = {'status': 'KEY_CREATED'}
        else:
            config = json.loads(Path(args.config).read_text())
            if args.command == 'backup':
                result = backup(config, args.directory)
            else:
                result = restore(config, args.directory, json.loads(Path(args.targets).read_text()), args.report)
        print(json.dumps(result))
    except Exception as failure:
        # Never forward driver, subprocess, SQL, environment or cryptography diagnostics containing private data.
        print(json.dumps({'status': 'FAILED', 'reason': str(failure) if isinstance(failure, OpsError) else 'Operation failed; inspect protected configuration and target state'}), file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
