import os
import sys
from pathlib import Path
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import staging_preflight as preflight
import recovery

RUNTIME='''spring:
  datasource:
    username: cbs_control_runtime
cbs:
  security:
    issuer: https://id.bank.co.ke/realms/cbs
    jwk-set-uri: https://id.bank.co.ke/realms/cbs/protocol/openid-connect/certs
    audience: cbs-api
  portal:
    client-id: cbs-portal
    redirect-uri: https://cbs.bank.co.ke/admin/index.html
  banking:
    databases:
      bank-reference:
        username: cbs_bank_runtime
'''
IDENTITY='''# private
KC_DB_URL=jdbc:postgresql://db.bank.co.ke:5432/cbs_identity?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/db-ca.crt
KC_DB_USERNAME=cbs_identity
KC_DB_PASSWORD=not-a-real-secret-value
'''

class StagingPreflightTests(unittest.TestCase):
    def test_mutable_image_tag_rejected(self):
        with self.assertRaisesRegex(recovery.OpsError,'immutable'):
            preflight.validate({'CBS_APP_IMAGE':'cbs:latest'})
    def test_placeholder_domain_rejected(self):
        values={'CBS_APP_IMAGE':'cbs@sha256:'+'a'*64,'CADDY_IMAGE':'caddy@sha256:'+'b'*64,'CBS_DOMAIN':'staging.example.test'}
        with self.assertRaisesRegex(recovery.OpsError,'real public'):
            preflight.validate(values)
    def test_missing_secret_file_rejected(self):
        values={'CBS_APP_IMAGE':'cbs@sha256:'+'a'*64,'CADDY_IMAGE':'caddy@sha256:'+'b'*64,'CBS_DOMAIN':'cbs.bank.co.ke','ACME_EMAIL':'ops@bank.co.ke'}
        with self.assertRaisesRegex(recovery.OpsError,'absolute regular file'):
            preflight.validate(values)

class IdentityPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.identity=Path(self.temp.name)/'identity.env'
        self.write(IDENTITY)
    def tearDown(self):
        self.temp.cleanup()
    def write(self,text):
        self.identity.write_text(text)
        if os.name!='nt':self.identity.chmod(0o600)
    def values(self,**overrides):
        values={'KEYCLOAK_IMAGE':'quay.io/keycloak/keycloak@sha256:'+'c'*64,'IDENTITY_DOMAIN':'id.bank.co.ke',
                'CBS_DOMAIN':'cbs.bank.co.ke','CBS_IDENTITY_ENV':str(self.identity)}
        values.update(overrides);return values
    def check(self,message,runtime=RUNTIME,**overrides):
        with self.assertRaisesRegex(recovery.OpsError,message) as caught:
            preflight.validate_identity(self.values(**overrides),runtime)
        self.assertNotIn('not-a-real-secret-value',str(caught.exception))

    def test_accepts_reviewed_configuration(self):
        preflight.validate_identity(self.values(),RUNTIME)
    def test_requires_pinned_keycloak_image(self):
        self.check('KEYCLOAK_IMAGE',KEYCLOAK_IMAGE='quay.io/keycloak/keycloak:26.7.4')
    def test_requires_separate_real_identity_domain(self):
        self.check('real public identity',IDENTITY_DOMAIN='id.example.test')
        self.check('must differ',IDENTITY_DOMAIN='cbs.bank.co.ke')
    def test_requires_verified_database_transport(self):
        for url in ('jdbc:postgresql://db/cbs_identity?sslmode=require',
                    'jdbc:postgresql://db/cbs_identity?sslmode=verify-full',
                    'jdbc:postgresql://db/cbs_identity?sslmode=verify-full&sslrootcert=/tmp/x.crt&sslmode=disable'):
            self.write(IDENTITY.replace(IDENTITY.splitlines()[1].split('=',1)[1],url))
            self.check('verify-full')
    def test_requires_own_database_login(self):
        self.write(IDENTITY.replace('KC_DB_USERNAME=cbs_identity','KC_DB_USERNAME=cbs_control_runtime'))
        self.check('own database login')
        self.write(IDENTITY.replace('KC_DB_PASSWORD=not-a-real-secret-value\n',''))
        self.check('username and password')
        self.write(IDENTITY.replace('not-a-real-secret-value','REPLACE_FROM_SECRET_STORE'))
        self.check('placeholders')
    def test_refuses_overrides_that_weaken_the_deployment(self):
        self.write(IDENTITY+'KC_HOSTNAME_STRICT=false\n')
        self.check('KC_HOSTNAME_STRICT')
    def test_runtime_must_trust_exactly_the_staging_realm(self):
        self.check('issuer',runtime=RUNTIME.replace('/realms/cbs\n','/realms/cbs-dev\n'))
        self.check('jwk-set-uri',runtime=RUNTIME.replace('id.bank.co.ke/realms/cbs/protocol','elsewhere.co.ke/realms/cbs/protocol'))
        self.check('client-id',runtime=RUNTIME.replace('client-id: cbs-portal','client-id: cbs-administration'))
        self.check('redirect-uri',runtime=RUNTIME.replace('https://cbs.bank.co.ke/admin','https://other.co.ke/admin'))
    @unittest.skipIf(os.name=='nt','POSIX permissions')
    def test_identity_secret_file_must_be_private(self):
        self.identity.chmod(0o644)
        self.check('group or other')

if __name__=='__main__':unittest.main()
