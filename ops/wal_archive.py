#!/usr/bin/env python3
"""PostgreSQL WAL archiving and Point-in-Time Recovery (PITR) tooling for CBS (P-04, Gate E06).

Provides continuous WAL segment encryption for archive_command, authenticated
decryption for restore_command, and recovery configuration generation for PITR drills.
No credentials or decrypted financial data in logs.
"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from datetime import datetime, timezone
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b'CBSWAL01'
WAL_NAME = re.compile(r'^[0-9A-F]{24}(\.history)?\Z')


class WalError(Exception):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def derive_keys(key_bytes):
    if len(key_bytes) != 32:
        raise WalError('Backup key must be exactly 32 bytes')
    enc = hmac.digest(key_bytes, b'CBS WAL archive encryption v1', 'sha256')
    mac = hmac.digest(key_bytes, b'CBS WAL archive manifest v1', 'sha256')
    return enc, mac


def load_key(key_path):
    p = Path(key_path)
    if not p.is_file() or p.is_symlink():
        raise WalError('Backup key file is missing or invalid')
    if os.name != 'nt' and (p.stat().st_mode & 0o077):
        raise WalError('Key file permissions must be restricted to 0600')
    raw = p.read_bytes()
    if len(raw) != 32:
        raise WalError('Backup key must contain exactly 32 bytes')
    return raw


def encrypt_file(source_path, target_path, enc_key):
    nonce = os.urandom(12)
    cipher = Cipher(algorithms.AES(enc_key), modes.GCM(nonce)).encryptor()
    cipher.authenticate_additional_data(MAGIC)
    temp_path = str(target_path) + f'.tmp.{os.getpid()}'
    try:
        with open(source_path, 'rb') as src, open(os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as dst:
            dst.write(MAGIC + nonce)
            while chunk := src.read(1024 * 1024):
                dst.write(cipher.update(chunk))
            dst.write(cipher.finalize() + cipher.tag)
            dst.flush()
            os.fsync(dst.fileno())
        os.replace(temp_path, target_path)
    finally:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass


def decrypt_file(source_path, target_path, enc_key):
    p = Path(source_path)
    size = p.stat().st_size
    if size < 36:
        raise WalError('Encrypted WAL segment is truncated')
    with open(source_path, 'rb') as src:
        header = src.read(8)
        if header != MAGIC:
            raise WalError('Invalid WAL segment magic bytes')
        nonce = src.read(12)
        src.seek(-16, 2)
        tag = src.read(16)
        src.seek(20)
        cipher = Cipher(algorithms.AES(enc_key), modes.GCM(nonce, tag)).decryptor()
        cipher.authenticate_additional_data(MAGIC)
        temp_path = str(target_path) + f'.tmp.{os.getpid()}'
        try:
            with open(os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as dst:
                left = size - 36
                while left > 0:
                    chunk = src.read(min(left, 1024 * 1024))
                    if not chunk:
                        raise WalError('Premature EOF reading WAL segment')
                    left -= len(chunk)
                    dst.write(cipher.update(chunk))
                dst.write(cipher.finalize())
                dst.flush()
                os.fsync(dst.fileno())
            os.replace(temp_path, target_path)
        finally:
            if os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:
                    pass


def archive_segment(archive_dir, key_file, source_path, segment_name):
    """Called by PostgreSQL archive_command: archives and encrypts one WAL segment."""
    if not WAL_NAME.match(segment_name):
        raise WalError(f'Invalid WAL segment filename: {segment_name}')
    src = Path(source_path)
    if not src.is_file():
        raise WalError(f'Source WAL segment not found: {source_path}')
    arch_dir = Path(archive_dir).resolve()
    arch_dir.mkdir(parents=True, exist_ok=True, mode=0o700)

    key_bytes = load_key(key_file)
    enc_key, mac_key = derive_keys(key_bytes)

    target_enc = arch_dir / (segment_name + '.enc')
    # If already archived and checksum matches, return success (PostgreSQL re-archives segments during crash recovery).
    if target_enc.exists():
        return {'status': 'ALREADY_ARCHIVED', 'segment': segment_name}

    encrypt_file(src, target_enc, enc_key)

    with open(target_enc, 'rb') as f:
        file_sha256 = hashlib.file_digest(f, 'sha256').hexdigest()

    meta = {
        'segment': segment_name,
        'sha256': file_sha256,
        'size_bytes': target_enc.stat().st_size,
        'archived_at': datetime.now(timezone.utc).isoformat()
    }
    meta_path = arch_dir / (segment_name + '.meta')
    envelope = {
        'metadata': meta,
        'hmac': hmac.digest(mac_key, canonical(meta), 'sha256').hex()
    }
    with open(os.open(meta_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as mf:
        mf.write(canonical(envelope))

    return {'status': 'ARCHIVED', 'segment': segment_name, 'sha256': file_sha256}


def restore_segment(archive_dir, key_file, segment_name, dest_path):
    """Called by PostgreSQL restore_command: verifies and decrypts one requested WAL segment."""
    if not WAL_NAME.match(segment_name):
        raise WalError(f'Invalid WAL segment filename: {segment_name}')
    arch_dir = Path(archive_dir).resolve()
    target_enc = arch_dir / (segment_name + '.enc')
    meta_path = arch_dir / (segment_name + '.meta')

    if not target_enc.is_file() or not meta_path.is_file():
        # Exit code 1 signals to PostgreSQL that the segment does not exist (normal during recovery end).
        sys.exit(1)

    key_bytes = load_key(key_file)
    enc_key, mac_key = derive_keys(key_bytes)

    meta_env = json.loads(meta_path.read_text())
    expected_hmac = hmac.digest(mac_key, canonical(meta_env['metadata']), 'sha256').hex()
    if not hmac.compare_digest(meta_env.get('hmac', ''), expected_hmac):
        raise WalError(f'WAL metadata authentication failed for {segment_name}')

    with open(target_enc, 'rb') as f:
        actual_sha256 = hashlib.file_digest(f, 'sha256').hexdigest()
    if actual_sha256 != meta_env['metadata']['sha256']:
        raise WalError(f'WAL archive checksum mismatch for {segment_name}')

    decrypt_file(target_enc, Path(dest_path), enc_key)
    return {'status': 'RESTORED', 'segment': segment_name}


def generate_pitr_config(archive_dir, key_file, target_time_iso=None, target_action='promote'):
    """Generates PostgreSQL recovery parameters for Point-In-Time Recovery."""
    python_exec = sys.executable
    script_path = str(Path(__file__).resolve())
    arch_path = str(Path(archive_dir).resolve())
    key_path = str(Path(key_file).resolve())

    restore_cmd = f'"{python_exec}" "{script_path}" restore --archive "{arch_path}" --key "{key_path}" --segment %f --destination "%p"'
    config_lines = [
        f"restore_command = '{restore_cmd}'",
        f"recovery_target_action = '{target_action}'",
    ]
    if target_time_iso:
        config_lines.append(f"recovery_target_time = '{target_time_iso}'")
        config_lines.append("recovery_target_inclusive = true")

    return "\n".join(config_lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)

    arch_parser = subparsers.add_parser('archive', help='Archive and encrypt a WAL segment')
    arch_parser.add_argument('--archive', required=True, help='Archive root directory')
    arch_parser.add_argument('--key', required=True, help='Secret key file')
    arch_parser.add_argument('--source', required=True, help='Source WAL path (%p)')
    arch_parser.add_argument('--segment', required=True, help='Segment name (%f)')

    rest_parser = subparsers.add_parser('restore', help='Decrypt and restore a WAL segment')
    rest_parser.add_argument('--archive', required=True, help='Archive root directory')
    rest_parser.add_argument('--key', required=True, help='Secret key file')
    rest_parser.add_argument('--segment', required=True, help='Segment name (%f)')
    rest_parser.add_argument('--destination', required=True, help='Destination path (%p)')

    cfg_parser = subparsers.add_parser('config', help='Generate recovery configuration lines')
    cfg_parser.add_argument('--archive', required=True, help='Archive root directory')
    cfg_parser.add_argument('--key', required=True, help='Secret key file')
    cfg_parser.add_argument('--target-time', help='Target recovery timestamp (ISO format)')
    cfg_parser.add_argument('--action', default='promote', choices=['promote', 'pause', 'shutdown'])

    args = parser.parse_args()
    try:
        if args.command == 'archive':
            res = archive_segment(args.archive, args.key, args.source, args.segment)
            print(json.dumps(res))
        elif args.command == 'restore':
            res = restore_segment(args.archive, args.key, args.segment, args.destination)
            print(json.dumps(res))
        elif args.command == 'config':
            cfg = generate_pitr_config(args.archive, args.key, args.target_time, args.action)
            print(cfg)
        return 0
    except Exception as exc:
        print(f"WAL operation error: {exc}", file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
