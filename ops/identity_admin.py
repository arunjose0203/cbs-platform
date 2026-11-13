"""Identity operations for the production realm (P-03): signing-key rotation and staff sign-in accounts.

Runs on the identity host against the loopback admin port, as the disabled-by-default ``cbs-identity-operator``
service identity. Enable that client for the operations window, run this tool, then disable it again.

    python3 ops/identity_admin.py --secret-file /etc/cbs/identity-operator.secret keys
    python3 ops/identity_admin.py --secret-file ... rotate
    python3 ops/identity_admin.py --secret-file ... retire --grace-minutes 60
    python3 ops/identity_admin.py --secret-file ... create-staff --username j.otieno --first-name J --last-name Otieno \
        --password-out /root/j.otieno.first-login

Output is JSON. Tokens, client secrets, temporary passwords and key material are never printed.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import secrets
import string
import sys
from urllib import error, parse, request

KEY_PROVIDER = 'org.keycloak.keys.KeyProvider'
ROTATED = re.compile(r'cbs-rsa-(\d{8}T\d{6}Z)\Z')
USERNAME = re.compile(r'[a-z0-9][a-z0-9._-]{1,62}\Z')
NAME = re.compile(r"[^\x00-\x1f<>\"']{1,100}\Z")


class IdentityError(Exception):
    pass


def secret(path):
    p = Path(path)
    if not p.is_file() or p.is_symlink():
        raise IdentityError('Operator secret file is missing or is a symbolic link')
    if os.name != 'nt' and p.stat().st_mode & 0o077:
        raise IdentityError('Operator secret file must not be readable by group or other users')
    value = p.read_text().strip()
    if len(value) < 16:
        raise IdentityError('Operator secret file looks empty')
    return value


def check_base(base):
    uri = parse.urlsplit(base)
    loopback = uri.hostname in ('127.0.0.1', 'localhost', '::1')
    if uri.scheme not in ('http', 'https') or (uri.scheme == 'http' and not loopback) or uri.username or uri.query:
        raise IdentityError('Use HTTPS, or plain HTTP only to the loopback admin port')
    return base.rstrip('/'), uri.scheme == 'http'


class Api:
    """Keycloak admin REST calls as the operator service identity."""

    def __init__(self, base, realm, client_id, client_secret, timeout=15.0):
        self.base, self.loopback_http = check_base(base)
        self.realm, self.timeout = realm, timeout
        form = parse.urlencode({'grant_type': 'client_credentials', 'client_id': client_id,
                                'client_secret': client_secret}).encode()
        token = self.send('POST', f'/realms/{realm}/protocol/openid-connect/token', form,
                          'application/x-www-form-urlencoded', auth=False)
        self.token = token.get('access_token') if isinstance(token, dict) else None
        if not self.token:
            raise IdentityError('The identity provider did not issue an operator token')

    def send(self, method, path, body=None, content_type='application/json', auth=True):
        req = request.Request(self.base + path, data=body, method=method)
        req.add_header('Accept', 'application/json')
        if body is not None:
            req.add_header('Content-Type', content_type)
        if auth:
            req.add_header('Authorization', 'Bearer ' + self.token)
        if self.loopback_http:
            # The realm requires TLS for every request; the edge normally asserts it. On the loopback admin
            # port the operator is already on the host, so it asserts the same thing explicitly.
            req.add_header('X-Forwarded-Proto', 'https')
        try:
            with request.urlopen(req, timeout=self.timeout) as response:
                raw = response.read()
                location = response.headers.get('Location')
        except error.HTTPError as failure:
            raise IdentityError(f'{method} {path.split("?")[0]} failed with HTTP {failure.code}') from None
        except (error.URLError, TimeoutError, OSError):
            raise IdentityError(f'{method} {path.split("?")[0]} could not reach the identity provider') from None
        if raw:
            return json.loads(raw)
        return {'location': location} if location else None

    def call(self, method, path, value=None):
        body = None if value is None else json.dumps(value).encode()
        return self.send(method, f'/admin/realms/{self.realm}{path}', body)


def key_status(api):
    keys = api.call('GET', '/keys')
    providers = {c['id']: c for c in api.call('GET', '/components?type=' + parse.quote(KEY_PROVIDER))}
    rows = []
    for key in keys.get('keys', []):
        if key.get('use') != 'SIG':
            continue
        provider = providers.get(key.get('providerId'), {})
        rows.append({'kid': key.get('kid'), 'algorithm': key.get('algorithm'), 'status': key.get('status'),
                     'priority': key.get('providerPriority'), 'provider': provider.get('name'),
                     'providerType': provider.get('providerId')})
    return {'active': keys.get('active', {}), 'keys': sorted(rows, key=lambda r: -(r['priority'] or 0))}


def signing_providers(api):
    return [c for c in api.call('GET', '/components?type=' + parse.quote(KEY_PROVIDER))
            if c.get('config', {}).get('algorithm', ['RS256'])[0] == 'RS256'
            and c.get('providerId') in ('rsa-generated', 'rsa')]


def first(component, name, default=None):
    return component.get('config', {}).get(name, [default])[0]


def rotate(api, now):
    """Add a new RS256 signing key above every other one and make the previous ones verify-only (passive)."""
    providers = signing_providers(api)
    realm_id = api.call('GET', '')['id']
    top = max([int(first(c, 'priority', '0')) for c in providers] + [100])
    name = 'cbs-rsa-' + now.strftime('%Y%m%dT%H%M%SZ')
    if any(c.get('name') == name for c in providers):
        raise IdentityError('A key was rotated in this second already; wait and retry')
    api.call('POST', '/components', {
        'name': name, 'providerId': 'rsa-generated', 'providerType': KEY_PROVIDER, 'parentId': realm_id,
        'config': {'priority': [str(top + 10)], 'keySize': ['3072'], 'algorithm': ['RS256'],
                   'active': ['true'], 'enabled': ['true']}})
    passive = []
    for component in providers:
        if first(component, 'active', 'true') == 'true' and first(component, 'enabled', 'true') == 'true':
            component['config']['active'] = ['false']
            api.call('PUT', f'/components/{component["id"]}', component)
            passive.append(component.get('name'))
    return {'status': 'ROTATED', 'newProvider': name, 'madePassive': passive}


def retire(api, now, grace):
    """Disable passive RS256 keys once the newest rotation is older than the grace period."""
    providers = signing_providers(api)
    active = [c for c in providers if first(c, 'active', 'true') == 'true' and first(c, 'enabled', 'true') == 'true']
    stamps = [ROTATED.match(c.get('name', '')) for c in active]
    if not active or not all(stamps):
        raise IdentityError('The active signing key was not created by rotate; retire only after a rotation')
    rotated = max(datetime.strptime(m.group(1), '%Y%m%dT%H%M%SZ').replace(tzinfo=timezone.utc) for m in stamps)
    if now - rotated < grace:
        raise IdentityError(f'Wait until {(rotated + grace).isoformat()} so tokens signed by the old key have expired')
    retired = []
    for component in providers:
        if first(component, 'active', 'true') == 'false' and first(component, 'enabled', 'true') == 'true':
            component['config']['enabled'] = ['false']
            api.call('PUT', f'/components/{component["id"]}', component)
            retired.append(component.get('name'))
    return {'status': 'RETIRED', 'disabled': retired, 'note': 'Disabled providers can be deleted after the next backup'}


def temporary_password():
    alphabet = string.ascii_letters + string.digits
    while True:
        value = ''.join(secrets.choice(alphabet) for _ in range(20)) + secrets.choice('!#%+-=?@') + secrets.choice(string.digits)
        if any(c.islower() for c in value) and any(c.isupper() for c in value):
            return value


def write_private(path, text):
    p = Path(path)
    if p.exists():
        raise IdentityError('Refusing to overwrite an existing password file')
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as handle:
        handle.write(text + '\n')


def create_staff(api, username, first_name, last_name, email, password_out):
    """Create a sign-in account that must set its own password and enroll OTP at first sign-in."""
    if not USERNAME.fullmatch(username):
        raise IdentityError('Username must be 2-63 lowercase letters, digits, dots, dashes or underscores')
    for value in (first_name, last_name):
        if not NAME.fullmatch(value):
            raise IdentityError('Names must be 1-100 printable characters without markup')
    if email and not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', email):
        raise IdentityError('Email address is not valid')
    if api.call('GET', '/users?exact=true&username=' + parse.quote(username)):
        raise IdentityError('A sign-in account with this username already exists')
    password = temporary_password()
    write_private(password_out, password)
    user = {'username': username, 'firstName': first_name, 'lastName': last_name, 'enabled': True,
            'requiredActions': ['UPDATE_PASSWORD', 'CONFIGURE_TOTP'],
            'credentials': [{'type': 'password', 'value': password, 'temporary': True}]}
    if email:
        user['email'], user['emailVerified'] = email, False
    try:
        api.call('POST', '/users', user)
    except IdentityError:
        Path(password_out).unlink(missing_ok=True)
        raise
    created = api.call('GET', '/users?exact=true&username=' + parse.quote(username))
    if len(created) != 1:
        raise IdentityError('The account was created but could not be read back; check the admin console')
    return {'status': 'CREATED', 'username': username, 'subject': created[0]['id'],
            'realm': api.realm, 'temporaryPasswordFile': str(password_out),
            'next': 'Register this subject as CBS staff, then hand over the password file through a separate channel'}


def main(argv=None, api_factory=Api, clock=lambda: datetime.now(timezone.utc)):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', default='http://127.0.0.1:8181')
    parser.add_argument('--realm', default='cbs')
    parser.add_argument('--client-id', default='cbs-identity-operator')
    parser.add_argument('--secret-file', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('keys')
    commands.add_parser('rotate')
    retire_cmd = commands.add_parser('retire')
    retire_cmd.add_argument('--grace-minutes', type=int, default=60)
    staff = commands.add_parser('create-staff')
    staff.add_argument('--username', required=True)
    staff.add_argument('--first-name', required=True)
    staff.add_argument('--last-name', required=True)
    staff.add_argument('--email', default='')
    staff.add_argument('--password-out', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'retire' and args.grace_minutes < 45:
            raise IdentityError('Grace must be at least 45 minutes: 15-minute tokens plus JWKS caching in CBS')
        api = api_factory(args.base, args.realm, args.client_id, secret(args.secret_file))
        if args.command == 'keys':
            result = key_status(api)
        elif args.command == 'rotate':
            result = rotate(api, clock())
        elif args.command == 'retire':
            result = retire(api, clock(), timedelta(minutes=args.grace_minutes))
        else:
            result = create_staff(api, args.username, args.first_name, args.last_name, args.email, args.password_out)
        print(json.dumps(result, indent=2))
        return 0
    except IdentityError as failure:
        print(json.dumps({'status': 'FAILED', 'reason': str(failure)}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
