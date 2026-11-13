"""P-03: the production realm keeps the controls CBS relies on, and stays aligned with the development realm."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PRODUCTION = json.loads((ROOT / 'config/keycloak/cbs-production-realm.json').read_text(encoding='utf-8'))
DEVELOPMENT = json.loads((ROOT / 'config/keycloak/cbs-dev-realm.json').read_text(encoding='utf-8'))


def client(realm, client_id):
    return next(c for c in realm['clients'] if c['clientId'] == client_id)


class ProductionRealmTests(unittest.TestCase):
    def test_transport_and_self_service_are_locked_down(self):
        self.assertEqual(PRODUCTION['realm'], 'cbs')
        self.assertEqual(PRODUCTION['sslRequired'], 'all')
        for flag in ('registrationAllowed', 'resetPasswordAllowed', 'rememberMe', 'editUsernameAllowed',
                     'loginWithEmailAllowed', 'otpPolicyCodeReusable', 'adminEventsDetailsEnabled'):
            self.assertIs(PRODUCTION[flag], False, flag)
        self.assertTrue(PRODUCTION['bruteForceProtected'])
        self.assertLessEqual(PRODUCTION['failureFactor'], 5)

    def test_every_browser_sign_in_needs_password_and_otp(self):
        flow = next(f for f in PRODUCTION['authenticationFlows'] if f['alias'] == PRODUCTION['browserFlow'])
        steps = {e['authenticator']: e['requirement'] for e in flow['authenticationExecutions']}
        self.assertEqual(steps, {'auth-username-password-form': 'REQUIRED', 'auth-otp-form': 'REQUIRED'})
        actions = {a['alias']: a for a in PRODUCTION['requiredActions']}
        self.assertTrue(actions['CONFIGURE_TOTP']['enabled'] and actions['CONFIGURE_TOTP']['defaultAction'])

    def test_mfa_evidence_matches_the_cbs_administrator_window(self):
        refs = {c['alias']: c['config'] for c in PRODUCTION['authenticatorConfig']}
        self.assertEqual(refs['cbs-mfa-reference']['default.reference.value'], 'mfa')
        # AccessConfiguration administratorMfaAge defaults to 30 minutes.
        self.assertEqual(refs['cbs-mfa-reference']['default.reference.maxAge'], '1800')

    def test_password_policy_is_at_least_as_strong_as_development(self):
        policy = PRODUCTION['passwordPolicy']
        self.assertGreaterEqual(int(re.search(r'length\((\d+)\)', policy).group(1)), 14)
        for rule in ('digits', 'upperCase', 'lowerCase', 'specialChars', 'notUsername', 'passwordHistory'):
            self.assertIn(rule, policy)

    def test_token_lifetimes_stay_within_cbs_limits(self):
        # AccessConfiguration: idle 15 minutes, token age 30 minutes, session 8 hours.
        self.assertLessEqual(PRODUCTION['accessTokenLifespan'], 1800)
        self.assertLessEqual(PRODUCTION['ssoSessionIdleTimeout'], 900)
        self.assertLessEqual(PRODUCTION['ssoSessionMaxLifespan'], 28800)
        self.assertTrue(PRODUCTION['revokeRefreshToken'])

    def test_portal_is_a_pkce_public_client_with_exact_https_uris(self):
        portal = client(PRODUCTION, 'cbs-portal')
        self.assertTrue(portal['publicClient'] and portal['standardFlowEnabled'])
        for flag in ('directAccessGrantsEnabled', 'implicitFlowEnabled', 'serviceAccountsEnabled', 'fullScopeAllowed'):
            self.assertIs(portal[flag], False, flag)
        self.assertEqual(portal['attributes']['pkce.code.challenge.method'], 'S256')
        self.assertEqual(portal['redirectUris'], ['https://${CBS_DOMAIN}/admin/index.html'])
        self.assertEqual(portal['webOrigins'], ['https://${CBS_DOMAIN}'])
        self.assertNotIn('*', json.dumps(portal))
        self.assertIn({'client': 'cbs-portal', 'roles': ['PLATFORM_ADMIN']}, PRODUCTION['scopeMappings'])

    def test_portal_tokens_carry_the_claims_cbs_validates(self):
        production = {m['protocolMapper']: m['config'] for m in client(PRODUCTION, 'cbs-portal')['protocolMappers']}
        development = {m['protocolMapper']: m['config'] for m in client(DEVELOPMENT, 'cbs-administration')['protocolMappers']}
        self.assertEqual(production, development)
        self.assertEqual(production['oidc-audience-mapper']['included.custom.audience'], 'cbs-api')
        self.assertEqual(production['oidc-usermodel-realm-role-mapper']['claim.name'], 'roles')

    def test_operator_service_identity_is_disabled_and_narrow(self):
        operator = client(PRODUCTION, 'cbs-identity-operator')
        self.assertIs(operator['enabled'], False)
        self.assertFalse(operator['publicClient'] or operator['standardFlowEnabled'] or operator['directAccessGrantsEnabled'])
        self.assertTrue(operator['serviceAccountsEnabled'])
        self.assertNotIn('secret', operator)
        account = next(u for u in PRODUCTION['users'] if u.get('serviceAccountClientId') == 'cbs-identity-operator')
        self.assertEqual(account['realmRoles'], [])
        self.assertEqual(set(account['clientRoles']), {'realm-management'})
        self.assertNotIn('realm-admin', account['clientRoles']['realm-management'])

    def test_no_people_or_secrets_in_the_template(self):
        humans = [u for u in PRODUCTION.get('users', []) if 'serviceAccountClientId' not in u]
        self.assertEqual(humans, [])
        def keys(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    yield key
                    yield from keys(value)
            elif isinstance(node, list):
                for value in node:
                    yield from keys(value)
        found = {k for k in keys(PRODUCTION) if k.lower() in ('secret', 'password', 'credentials', 'privatekey')}
        self.assertEqual(found, set())
        self.assertNotIn('components', PRODUCTION, 'signing keys are generated by Keycloak, never shipped')

    def test_only_documented_placeholders(self):
        placeholders = set(re.findall(r'\$\{([A-Z_]+)', json.dumps(PRODUCTION)))
        self.assertEqual(placeholders, {'CBS_DOMAIN'})


if __name__ == '__main__':
    unittest.main()
