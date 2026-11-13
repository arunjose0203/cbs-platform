"""Unit tests for M-of-N Key Escrow and Split-Knowledge Secret Sharing (P-04, Gate E06)."""
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import key_escrow


class KeyEscrowTests(unittest.TestCase):
    def test_split_and_reconstruct_2_of_3(self):
        original_key = os.urandom(32)
        shares = key_escrow.split_secret(original_key, n=3, k=2)
        self.assertEqual(len(shares), 3)

        # Any combination of 2 shares must reconstruct the key
        self.assertEqual(key_escrow.recover_secret([shares[0], shares[1]]), original_key)
        self.assertEqual(key_escrow.recover_secret([shares[0], shares[2]]), original_key)
        self.assertEqual(key_escrow.recover_secret([shares[1], shares[2]]), original_key)
        # All 3 shares must also reconstruct the key
        self.assertEqual(key_escrow.recover_secret(shares), original_key)

    def test_split_and_reconstruct_3_of_5(self):
        original_key = os.urandom(32)
        shares = key_escrow.split_secret(original_key, n=5, k=3)
        self.assertEqual(len(shares), 5)

        # 3 shares reconstruct
        self.assertEqual(key_escrow.recover_secret([shares[0], shares[2], shares[4]]), original_key)
        self.assertEqual(key_escrow.recover_secret([shares[1], shares[3], shares[4]]), original_key)

        # Fewer than 3 shares must raise EscrowError
        with self.assertRaises(key_escrow.EscrowError):
            key_escrow.recover_secret([shares[0], shares[1]])

    def test_tampered_share_fails_integrity(self):
        original_key = os.urandom(32)
        shares = key_escrow.split_secret(original_key, n=3, k=2)

        # Corrupt one share
        tampered = list(shares)
        tampered[0] = dict(tampered[0])
        tampered[0]['share'] = '0' * 64

        with self.assertRaises(key_escrow.EscrowError):
            key_escrow.recover_secret([tampered[0], tampered[1]])

    def test_duplicate_share_index_rejected(self):
        original_key = os.urandom(32)
        shares = key_escrow.split_secret(original_key, n=3, k=2)
        with self.assertRaises(key_escrow.EscrowError):
            key_escrow.recover_secret([shares[0], shares[0]])


if __name__ == '__main__':
    unittest.main()
