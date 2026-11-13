"""Unit tests for PostgreSQL WAL archiving and PITR tooling (P-04, Gate E06)."""
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import wal_archive


class WalArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.archive_dir = Path(self.temp_dir) / 'wal_archive'
        self.key_file = Path(self.temp_dir) / 'backup.key'
        self.key_bytes = os.urandom(32)
        self.key_file.write_bytes(self.key_bytes)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_archive_and_restore_roundtrip(self):
        source_wal = Path(self.temp_dir) / '000000010000000000000001'
        sample_data = b'CBS-WAL-SEGMENT-CONTENT-XYZ' * 1024
        source_wal.write_bytes(sample_data)

        # Archive segment
        res = wal_archive.archive_segment(str(self.archive_dir), str(self.key_file), str(source_wal), source_wal.name)
        self.assertEqual(res['status'], 'ARCHIVED')
        self.assertTrue((self.archive_dir / (source_wal.name + '.enc')).is_file())
        self.assertTrue((self.archive_dir / (source_wal.name + '.meta')).is_file())

        # Restore segment to a new location
        restored_wal = Path(self.temp_dir) / 'restored_segment'
        rest_res = wal_archive.restore_segment(str(self.archive_dir), str(self.key_file), source_wal.name, str(restored_wal))
        self.assertEqual(rest_res['status'], 'RESTORED')
        self.assertEqual(restored_wal.read_bytes(), sample_data)

    def test_rearchive_same_segment_is_idempotent(self):
        source_wal = Path(self.temp_dir) / '000000010000000000000002'
        source_wal.write_bytes(b'WAL-CONTENT')
        res1 = wal_archive.archive_segment(str(self.archive_dir), str(self.key_file), str(source_wal), source_wal.name)
        self.assertEqual(res1['status'], 'ARCHIVED')

        res2 = wal_archive.archive_segment(str(self.archive_dir), str(self.key_file), str(source_wal), source_wal.name)
        self.assertEqual(res2['status'], 'ALREADY_ARCHIVED')

    def test_tampered_metadata_fails_authentication(self):
        source_wal = Path(self.temp_dir) / '000000010000000000000003'
        source_wal.write_bytes(b'VALID-CONTENT')
        wal_archive.archive_segment(str(self.archive_dir), str(self.key_file), str(source_wal), source_wal.name)

        # Tamper with the metadata file
        meta_file = self.archive_dir / (source_wal.name + '.meta')
        meta_file.write_text('{"metadata": {"segment": "tampered"}, "hmac": "deadbeef"}')

        restored_dest = Path(self.temp_dir) / 'tampered_output'
        with self.assertRaises(wal_archive.WalError):
            wal_archive.restore_segment(str(self.archive_dir), str(self.key_file), source_wal.name, str(restored_dest))

    def test_generate_pitr_config(self):
        cfg = wal_archive.generate_pitr_config(str(self.archive_dir), str(self.key_file), target_time_iso='2026-11-14T10:00:00Z', target_action='promote')
        self.assertIn('restore_command =', cfg)
        self.assertIn('recovery_target_time = \'2026-11-14T10:00:00Z\'', cfg)
        self.assertIn('recovery_target_action = \'promote\'', cfg)


if __name__ == '__main__':
    unittest.main()
