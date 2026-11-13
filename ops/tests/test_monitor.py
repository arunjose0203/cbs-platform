import io
import json
import hmac
from datetime import datetime,timezone,timedelta
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import recovery
import monitor

class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.now=datetime.now(timezone.utc).isoformat();self.signing=b'x'*32
        self.config={'databases':[{'name':'control','kind':'control'}],'monitor':{'readiness_url':'https://cbs.example/health/ready','last_backup_job':str(self.root/'job'),'latest_restore_report':str(self.root/'restore'),'max_backup_age_seconds':3600,'max_restore_age_seconds':86400,'max_pending_age_seconds':600}}
        (self.root/'job').write_text(json.dumps({'status':'BACKUP_COMPLETE','directory':'backup'}))
        report={'status':'RESTORE_VERIFIED','completed_at':self.now}
        (self.root/'restore').write_text(json.dumps({'report':report,'hmac':hmac.digest(self.signing,recovery.canonical(report),'sha256').hex()}))
    def tearDown(self):self.temp.cleanup()
    def check(self,*,pending=0,backup_time=None,db_error=False):
        def query(_,d,sql):
            if db_error:raise recovery.OpsError('database unavailable')
            if sql=='SELECT 1':return '1'
            return '["journal_delivery"]' if 'information_schema' in sql else str(pending)
        with patch.object(recovery,'validate'),patch.object(recovery,'keys',return_value=(b'y'*32,self.signing)),patch.object(recovery,'authenticated_manifest',return_value=({'completed_at':backup_time or self.now},None)),patch.object(recovery,'query',side_effect=query),patch.object(monitor.urllib.request,'urlopen',return_value=io.BytesIO(b'{"status":"UP"}')):
            return monitor.check(self.config)
    def test_healthy_evidence_and_database(self):self.assertEqual(self.check()['status'],'OK')
    def test_failed_backup_not_hidden_by_fresh_previous_backup(self):
        (self.root/'job').write_text('{"status":"WRITER_RESTART_FAILED"}')
        self.assertIn('BACKUP_JOB_FAILED',self.check()['alerts'])
    def test_stale_backup_and_stuck_posting(self):
        result=self.check(pending=601,backup_time=(datetime.now(timezone.utc)-timedelta(hours=2)).isoformat())
        self.assertIn('BACKUP_STALE',result['alerts']);self.assertIn('POSTING_OVERDUE:journal_delivery',result['alerts'])
    def test_modified_restore_evidence(self):
        (self.root/'restore').write_text('{"report":{"status":"RESTORE_VERIFIED"},"hmac":"invalid"}')
        self.assertIn('RESTORE_EVIDENCE_MISSING_OR_INVALID',self.check()['alerts'])
    def test_overdue_compliance_cases_are_reported_as_a_count(self):
        self.assertEqual(self.check(pending=3)['metrics']['cbs_compliance_overdue_cases'],3)
    def test_database_failure(self):self.assertIn('DATABASE_CHECK_FAILED:control',self.check(db_error=True)['alerts'])

if __name__=='__main__':unittest.main()
