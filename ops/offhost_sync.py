#!/usr/bin/env python3
"""Off-Host Backup Synchronization and Retention Enforcement for CBS (P-04, Gate E06).

Validates off-host immutable storage replication receipts, ensures cryptographic
integrity against remote transport checksums, and applies the statutory retention
matrix without ever deleting the last known-good recovery set.
"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
from datetime import datetime, timezone, timedelta


class SyncError(Exception):
    pass


def canonical(val):
    return json.dumps(val, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def verify_receipt(backup_dir, receipt_path, signing_key):
    """Verifies that an off-host immutable storage target accurately received the backup set."""
    b_dir = Path(backup_dir).resolve()
    r_path = Path(receipt_path).resolve()

    if not b_dir.is_dir() or not (b_dir / 'manifest.json').is_file():
        raise SyncError('Local backup directory or manifest is missing')
    if not r_path.is_file():
        raise SyncError('Remote storage receipt file is missing')

    receipt_envelope = json.loads(r_path.read_text())
    receipt = receipt_envelope.get('receipt', {})
    received_hmac = receipt_envelope.get('hmac', '')

    expected_hmac = hmac.digest(signing_key, canonical(receipt), 'sha256').hex()
    if not hmac.compare_digest(received_hmac, expected_hmac):
        raise SyncError('Remote storage receipt HMAC authentication failed')

    # Compare local files and remote checksums
    remote_files = {item['file']: item['sha256'] for item in receipt.get('items', [])}
    local_manifest_env = json.loads((b_dir / 'manifest.json').read_text())
    local_manifest = local_manifest_env.get('manifest', {})

    for item in local_manifest.get('databases', []) + local_manifest.get('files', []):
        filename = item['file']
        if filename not in remote_files:
            raise SyncError(f'Remote receipt missing file: {filename}')
        if remote_files[filename] != item['sha256']:
            raise SyncError(f'Remote receipt checksum mismatch for {filename}')

    return {
        'status': 'OFFHOST_RECEIPT_VERIFIED',
        'remote_storage_id': receipt.get('remote_storage_id'),
        'verified_items': len(remote_files),
        'timestamp': receipt.get('received_at')
    }


def prune_expired_backups(backup_root, max_daily_days=30, dry_run=True):
    """Applies retention policy to historical backup sets.

    Crucial safety invariant: NEVER deletes the newest completed backup or an incomplete set.
    """
    root = Path(backup_root).resolve()
    if not root.is_dir():
        raise SyncError(f'Backup directory not found: {backup_root}')

    candidates = []
    for item in root.iterdir():
        if item.is_dir() and (item / 'manifest.json').is_file():
            try:
                m_env = json.loads((item / 'manifest.json').read_text())
                m = m_env.get('manifest', {})
                completed_at = datetime.fromisoformat(m['completed_at'])
                candidates.append((completed_at, item))
            except Exception:
                continue

    if not candidates:
        return {'retained': 0, 'pruned': 0, 'actions': []}

    # Sort ascending by completion time
    candidates.sort(key=lambda x: x[0])
    newest_backup = candidates[-1][1]

    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=max_daily_days)

    actions = []
    pruned_count = 0
    retained_count = 0

    for completed_at, dir_path in candidates:
        # Never prune the newest backup set regardless of age
        if dir_path == newest_backup:
            retained_count += 1
            continue

        if completed_at < cutoff:
            actions.append({'directory': str(dir_path), 'action': 'PRUNE', 'completed_at': completed_at.isoformat()})
            pruned_count += 1
            if not dry_run:
                # Remove files in directory then directory
                for f in dir_path.iterdir():
                    f.unlink()
                dir_path.rmdir()
        else:
            retained_count += 1

    return {
        'retained': retained_count,
        'pruned': pruned_count,
        'dry_run': dry_run,
        'actions': actions
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)

    rec_p = subparsers.add_parser('verify-receipt')
    rec_p.add_argument('--backup-dir', required=True)
    rec_p.add_argument('--receipt-file', required=True)
    rec_p.add_argument('--key-file', required=True)

    ret_p = subparsers.add_parser('retention')
    ret_p.add_argument('--backup-root', required=True)
    ret_p.add_argument('--days', type=int, default=30)
    ret_p.add_argument('--apply', action='store_true', help='Perform actual deletion (defaults to dry-run)')

    args = parser.parse_args()
    try:
        if args.command == 'verify-receipt':
            key = Path(args.key_file).read_bytes()
            signing_key = hmac.digest(key, b'CBS manifest v1', 'sha256')
            res = verify_receipt(args.backup_dir, args.receipt_file, signing_key)
            print(json.dumps(res))
        elif args.command == 'retention':
            res = prune_expired_backups(args.backup_root, max_daily_days=args.days, dry_run=not args.apply)
            print(json.dumps(res))
        return 0
    except Exception as err:
        print(f"Off-host sync error: {err}", file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
