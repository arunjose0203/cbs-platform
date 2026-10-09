import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import staging_preflight as preflight
import recovery

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

if __name__=='__main__':unittest.main()
