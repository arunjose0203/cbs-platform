import io
import json
import hmac
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import recovery
import monitor

class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.key=os.urandom(32)
        recovery.private_write(self.root/'key',self.key)
        self.config={'key_file':str(self.root/'key')}
    def tearDown(self):self.temp.cleanup()
    def archive(self):
        path=self.root/'data.enc';recovery.encrypt(io.BytesIO(b'fictional ledger\n'*10000),path,self.key);return path
    def test_encrypted_roundtrip(self):
        path=self.archive();out=io.BytesIO();recovery.decrypt(path,out,self.key)
        self.assertEqual(out.getvalue(),b'fictional ledger\n'*10000)
        self.assertNotIn(b'fictional ledger',path.read_bytes())
    def test_corruption_and_wrong_key_rejected(self):
        path=self.archive()
        with self.assertRaises(Exception):recovery.decrypt(path,io.BytesIO(),os.urandom(32))
        data=bytearray(path.read_bytes());data[30]^=1;path.write_bytes(data)
        with self.assertRaises(Exception):recovery.decrypt(path,io.BytesIO(),self.key)
    def test_truncation_rejected(self):
        path=self.root/'bad';path.write_bytes(b'CBSBACK1')
        with self.assertRaises(recovery.OpsError):recovery.decrypt(path,io.BytesIO(),self.key)
    def test_existing_archive_not_overwritten(self):
        path=self.archive()
        with self.assertRaises(FileExistsError):recovery.encrypt(io.BytesIO(b'x'),path,self.key)
    def signed(self,filename='data.enc'):
        path=self.archive();m={'format':1,'databases':[],'files':[{'file':filename,'sha256':recovery.digest(path)}]}
        (self.root/'manifest.json').write_text(json.dumps({'manifest':m,'hmac':hmac.digest(recovery.keys(self.config)[1],recovery.canonical(m),'sha256').hex()}))
    def test_manifest_and_checksum(self):
        self.signed();recovery.authenticated_manifest(self.config,self.root)
        (self.root/'data.enc').write_bytes(b'tampered')
        with self.assertRaises(recovery.OpsError):recovery.authenticated_manifest(self.config,self.root)
    def test_manifest_tampering(self):
        self.signed();p=self.root/'manifest.json';v=json.loads(p.read_text());v['manifest']['format']=2;p.write_text(json.dumps(v))
        with self.assertRaises(recovery.OpsError):recovery.authenticated_manifest(self.config,self.root)
    def test_signed_path_traversal_rejected(self):
        self.signed('../data.enc')
        with self.assertRaises(recovery.OpsError):recovery.authenticated_manifest(self.config,self.root)
    def test_invalid_key_length(self):
        (self.root/'key').write_bytes(b'short')
        with self.assertRaises(recovery.OpsError):recovery.keys(self.config)
    def test_production_thresholds_required(self):
        with patch.object(recovery,'validate'), self.assertRaises(recovery.OpsError):
            monitor.check({'monitor':dict(max_backup_age_seconds=0,max_restore_age_seconds=1,max_pending_age_seconds=1)})
    def test_nonempty_restore_refused_before_pg_restore(self):
        d={'name':'bank','kind':'bank','tenant_id':'test','database':'cbs_restore_test','host':'localhost','port':5432}
        m={'databases':[dict(d,source=['localhost',5432,'source'])]}
        with patch.object(recovery,'validate'),patch.object(recovery,'authenticated_manifest',return_value=(m,self.key)),patch.object(recovery,'tables',return_value=[['bank','journal']]),patch.object(recovery.subprocess,'run') as run:
            with self.assertRaises(recovery.OpsError):recovery.restore({},self.root,{'databases':[d]},self.root/'report')
            run.assert_not_called()

if __name__=='__main__':unittest.main()
