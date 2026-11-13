import contextlib
import copy
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import identity_admin as admin

NOW = datetime(2026, 11, 14, 12, 0, 0, tzinfo=timezone.utc)
SECRET = 'operator-secret-not-real-0123456789'


def provider(id, name, priority, active='true', enabled='true', provider_id='rsa-generated', algorithm='RS256'):
    return {'id': id, 'name': name, 'providerId': provider_id, 'providerType': admin.KEY_PROVIDER, 'parentId': 'realm-1',
            'config': {'priority': [str(priority)], 'active': [active], 'enabled': [enabled], 'algorithm': [algorithm]}}


class FakeApi:
    """Records admin calls and serves a small in-memory realm."""

    def __init__(self, components):
        self.realm, self.calls, self.users = 'cbs', [], []
        self.components = {c['id']: c for c in components}

    def call(self, method, path, value=None):
        self.calls.append((method, path, copy.deepcopy(value)))
        if method == 'GET' and path == '':
            return {'id': 'realm-1'}
        if method == 'GET' and path.startswith('/components'):
            return copy.deepcopy(list(self.components.values()))
        if method == 'POST' and path == '/components':
            self.components['new'] = dict(value, id='new')
            return {'location': '.../components/new'}
        if method == 'PUT' and path.startswith('/components/'):
            self.components[path.rsplit('/', 1)[1]] = value
            return None
        if method == 'GET' and path == '/keys':
            return {'active': {'RS256': 'kid-new'}, 'keys': [
                {'kid': 'kid-new', 'algorithm': 'RS256', 'status': 'ACTIVE', 'providerId': 'a', 'providerPriority': 110,
                 'use': 'SIG', 'publicKey': 'MIIB...', 'certificate': 'MIIC...'},
                {'kid': 'kid-enc', 'algorithm': 'RSA-OAEP', 'status': 'ACTIVE', 'providerId': 'b', 'use': 'ENC'}]}
        if method == 'GET' and path.startswith('/users'):
            return [u for u in self.users if u['username'] in path]
        if method == 'POST' and path == '/users':
            self.users.append(dict(value, id='subject-123'))
            return {'location': '.../users/subject-123'}
        raise AssertionError((method, path))


class RotationTests(unittest.TestCase):
    def test_rotate_adds_a_higher_priority_key_and_keeps_old_keys_for_verification(self):
        api = FakeApi([provider('a', 'rsa-generated', 100), provider('enc', 'rsa-enc-generated', 100, algorithm='RSA-OAEP',
                                                                        provider_id='rsa-enc-generated'),
                       provider('h', 'hmac-generated', 100, provider_id='hmac-generated', algorithm='HS512')])
        result = admin.rotate(api, NOW)
        self.assertEqual(result, {'status': 'ROTATED', 'newProvider': 'cbs-rsa-20261114T120000Z', 'madePassive': ['rsa-generated']})
        created = next(v for m, p, v in api.calls if m == 'POST')
        self.assertEqual(created['config']['priority'], ['110'])
        self.assertEqual(created['config']['keySize'], ['3072'])
        self.assertEqual(created['parentId'], 'realm-1')
        self.assertEqual(api.components['a']['config']['active'], ['false'])
        self.assertEqual(api.components['a']['config']['enabled'], ['true'])
        self.assertEqual(api.components['enc']['config']['active'], ['true'])
        self.assertEqual(api.components['h']['config']['active'], ['true'])
        post = next(i for i, c in enumerate(api.calls) if c[0] == 'POST')
        put = next(i for i, c in enumerate(api.calls) if c[0] == 'PUT')
        self.assertLess(post, put, 'the new key must exist before the old one stops signing')

    def test_retire_waits_for_the_grace_period(self):
        api = FakeApi([provider('a', 'rsa-generated', 100, active='false'), provider('n', 'cbs-rsa-20261114T113000Z', 110)])
        with self.assertRaisesRegex(admin.IdentityError, 'Wait until 2026-11-14T12:30:00'):
            admin.retire(api, NOW, admin.timedelta(minutes=60))
        self.assertFalse(any(m == 'PUT' for m, _, _ in api.calls))
        result = admin.retire(api, NOW, admin.timedelta(minutes=30))
        self.assertEqual(result['disabled'], ['rsa-generated'])
        self.assertEqual(api.components['a']['config']['enabled'], ['false'])
        self.assertEqual(api.components['n']['config']['enabled'], ['true'])

    def test_retire_refuses_when_no_rotation_happened(self):
        api = FakeApi([provider('a', 'rsa-generated', 100)])
        with self.assertRaisesRegex(admin.IdentityError, 'after a rotation'):
            admin.retire(api, NOW, admin.timedelta(minutes=60))

    def test_key_status_never_includes_key_material(self):
        api = FakeApi([provider('a', 'cbs-rsa-20261114T113000Z', 110)])
        text = json.dumps(admin.key_status(api))
        self.assertNotIn('MII', text)
        self.assertEqual([k['kid'] for k in json.loads(text)['keys']], ['kid-new'])
        self.assertIn('cbs-rsa-20261114T113000Z', text)


class StaffTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.out = Path(self.temp.name) / 'first-login'

    def tearDown(self):
        self.temp.cleanup()

    def test_creates_account_that_must_change_password_and_enroll_otp(self):
        api = FakeApi([])
        result = admin.create_staff(api, 'j.otieno', 'Jane', 'Otieno', 'j.otieno@bank.co.ke', self.out)
        user = api.users[0]
        self.assertEqual(user['requiredActions'], ['UPDATE_PASSWORD', 'CONFIGURE_TOTP'])
        self.assertTrue(user['credentials'][0]['temporary'])
        password = self.out.read_text().strip()
        self.assertEqual(user['credentials'][0]['value'], password)
        self.assertGreaterEqual(len(password), 14)
        self.assertNotIn(password, json.dumps(result))
        self.assertEqual(result['subject'], 'subject-123')
        if os.name != 'nt':
            self.assertEqual(self.out.stat().st_mode & 0o077, 0)

    def test_refuses_duplicates_bad_names_and_existing_password_files(self):
        api = FakeApi([])
        api.users.append({'username': 'j.otieno', 'id': 'x'})
        with self.assertRaisesRegex(admin.IdentityError, 'already exists'):
            admin.create_staff(api, 'j.otieno', 'Jane', 'Otieno', '', self.out)
        self.assertFalse(self.out.exists())
        for username in ('J.Otieno', 'a', 'x' * 64, 'bad name'):
            with self.assertRaisesRegex(admin.IdentityError, 'Username'):
                admin.create_staff(FakeApi([]), username, 'Jane', 'Otieno', '', self.out)
        with self.assertRaisesRegex(admin.IdentityError, 'Names'):
            admin.create_staff(FakeApi([]), 'jane', '<script>', 'Otieno', '', self.out)
        self.out.write_text('keep')
        with self.assertRaisesRegex(admin.IdentityError, 'overwrite'):
            admin.create_staff(FakeApi([]), 'jane', 'Jane', 'Otieno', '', self.out)
        self.assertEqual(self.out.read_text(), 'keep')


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.secret = Path(self.temp.name) / 'operator.secret'
        self.secret.write_text(SECRET)
        if os.name != 'nt':
            self.secret.chmod(0o600)

    def tearDown(self):
        self.temp.cleanup()

    def run_main(self, *args, factory=None):
        seen = {}

        def default_factory(base, realm, client_id, client_secret):
            seen.update(base=base, realm=realm, client_id=client_id, secret=client_secret)
            return FakeApi([provider('a', 'rsa-generated', 100)])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = admin.main(['--secret-file', str(self.secret), *args], factory or default_factory, lambda: NOW)
        return code, out.getvalue(), seen

    def test_rotate_through_the_command_line_without_printing_the_secret(self):
        code, output, seen = self.run_main('rotate')
        self.assertEqual(code, 0, output)
        self.assertEqual(seen, {'base': 'http://127.0.0.1:8181', 'realm': 'cbs', 'client_id': 'cbs-identity-operator',
                                'secret': SECRET})
        self.assertNotIn(SECRET, output)
        self.assertEqual(json.loads(output)['status'], 'ROTATED')

    def test_short_grace_is_refused_before_contacting_the_provider(self):
        code, output, seen = self.run_main('retire', '--grace-minutes', '10')
        self.assertEqual(code, 1)
        self.assertIn('at least 45 minutes', output)
        self.assertEqual(seen, {})

    def test_failures_are_reported_without_secrets(self):
        def failing(*_):
            raise admin.IdentityError('POST /realms/cbs/protocol/openid-connect/token failed with HTTP 401')
        code, output, _ = self.run_main('keys', factory=failing)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output)['status'], 'FAILED')
        self.assertNotIn(SECRET, output)

    @unittest.skipIf(os.name == 'nt', 'POSIX permissions')
    def test_readable_secret_file_is_refused(self):
        self.secret.chmod(0o644)
        code, output, seen = self.run_main('keys')
        self.assertEqual(code, 1)
        self.assertIn('group or other', output)


class BaseUrlTests(unittest.TestCase):
    def test_plain_http_only_on_loopback(self):
        self.assertEqual(admin.check_base('http://127.0.0.1:8181/'), ('http://127.0.0.1:8181', True))
        self.assertEqual(admin.check_base('https://id.bank.co.ke'), ('https://id.bank.co.ke', False))
        for base in ('http://id.bank.co.ke', 'https://user:pw@id.bank.co.ke', 'ftp://127.0.0.1', 'https://id.bank.co.ke?x=1'):
            with self.assertRaises(admin.IdentityError):
                admin.check_base(base)


if __name__ == '__main__':
    unittest.main()
