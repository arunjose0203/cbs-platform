"""Unit tests for off-host backup sync verification and retention enforcement (P-04, Gate E06)."""
from datetime import datetime, timezone, timedelta
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import offhost_sync


class OffhostSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.backup_dir = Path(self.temp_dir) / 'backup_20261114_010000'
        self.backup_dir.mkdir(parents=True)
        self.key_bytes = os.urandom(32)
        self.signing_key = hmac.digest(self.key_bytes, b'CBS manifest v1', 'sha256')

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_verify_receipt_valid(self):
        # Create fake backup files
        file1 = self.backup_dir / 'db-control.enc'
        file1.write_bytes(b'ENCRYPTED-DB-DATA')
        sha = hashlib.sha256(b'ENCRYPTED-DB-DATA').hexdigest()

        manifest = {
            'format': 1,
            'completed_at': '2026-11-14T01:00:00+00:00',
            'databases': [{'name': 'control', 'file': 'db-control.enc', 'sha256': sha}],
            'files': []
        }
        (self.backup_dir / 'manifest.json').write_text(json.dumps({'manifest': manifest}))

        # Create valid remote receipt
        receipt = {
            'remote_storage_id': 'immutable-vault-vault-01',
            'received_at': '2026-11-14T01:05:00+00:00',
            'items': [{'file': 'db-control.enc', 'sha256': sha}]
        }
        receipt_hmac = hmac.digest(self.signing_key, offhost_sync.canonical(receipt), 'sha256').hex()
        receipt_file = Path(self.temp_dir) / 'receipt.json'
        receipt_file.write_text(json.dumps({'receipt': receipt, 'hmac': receipt_hmac}))

        res = offhost_sync.verify_receipt(str(self.backup_dir), str(receipt_file), self.signing_key)
        self.assertEqual(res['status'], 'OFFHOST_RECEIPT_VERIFIED')
        self.assertEqual(res['verified_items'], 1)

    def test_retention_never_prunes_newest_backup(self):
        root = Path(self.temp_dir) / 'all_backups'
        root.mkdir()

        # Create 3 backups: 60 days old, 40 days old, 35 days old
        now = datetime.now(timezone.utc)
        for days_old in [60, 40, 35]:
            b_dir = root / f"backup_{days_old}d"
            b_dir.mkdir()
            dt = now - timedelta(days=days_old)
            m = {'completed_at': dt.isoformat(), 'databases': [], 'files': []}
            (b_dir / 'manifest.json').write_text(json.dumps({'manifest': m}))

        # With 30 days retention, the 60d and 40d backups are pruned, but 35d backup is the newest, so it MUST be kept!
        res = offhost_sync.prune_expired_backups(str(root), max_daily_days=30, dry_run=False)
        self.assertEqual(res['retained'], 1)
        self.assertEqual(res['pruned'], 2)
        self.assertTrue((root / 'backup_35d').exists())
        self.assertFalse((root / 'backup_60d').exists())
        self.assertFalse((root / 'backup_40d').exists())


if __name__ == '__main__':
    unittest.main()
