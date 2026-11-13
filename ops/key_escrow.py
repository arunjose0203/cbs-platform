#!/usr/bin/env python3
"""M-of-N Key Escrow and Split-Knowledge Secret Sharing for CBS Recovery Keys (P-04, Gate E06).

Implements Shamir's threshold secret sharing over a 256-bit prime field, allowing
the 32-byte master backup key to be escrowed across multiple authorized custodians
(e.g., 2-of-3 threshold between Risk Officer, Head of Engineering, and External Auditor).
Never prints key material or unprotected shares in logs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

# Prime field slightly larger than 2^256: 2^256 + 297
FIELD_PRIME = 115792089237316195423570985008687907853269984665640564039457584007913129640233


class EscrowError(Exception):
    pass


def egcd(a, b):
    if a == 0:
        return b, 0, 1
    gcd, x1, y1 = egcd(b % a, a)
    x = y1 - (b // a) * x1
    y = x1
    return gcd, x, y


def mod_inverse(k, prime=FIELD_PRIME):
    k = k % prime
    if k < 0:
        k += prime
    gcd, x, _ = egcd(k, prime)
    if gcd != 1:
        raise EscrowError('Modular inverse does not exist')
    return (x % prime + prime) % prime


def split_secret(key_bytes, n=3, k=2):
    """Splits a 32-byte secret into n shares with threshold k."""
    if len(key_bytes) != 32:
        raise EscrowError('Secret key must be exactly 32 bytes')
    if k < 2 or k > n or n > 16:
        raise EscrowError('Invalid threshold: require 2 <= k <= n <= 16')

    secret_int = int.from_bytes(key_bytes, byteorder='big')
    fingerprint = hashlib.sha256(key_bytes).hexdigest()

    # Generate k-1 random coefficients
    coefficients = [secret_int]
    for _ in range(k - 1):
        rand_bytes = os.urandom(32)
        coef = int.from_bytes(rand_bytes, byteorder='big') % FIELD_PRIME
        coefficients.append(coef)

    shares = []
    for x in range(1, n + 1):
        # Evaluate polynomial at x
        y = 0
        for exp, coef in enumerate(coefficients):
            term = (coef * pow(x, exp, FIELD_PRIME)) % FIELD_PRIME
            y = (y + term) % FIELD_PRIME

        share_data = {
            'index': x,
            'threshold': k,
            'total_shares': n,
            'share': f"{y:064x}",
            'fingerprint': fingerprint
        }
        shares.append(share_data)

    return shares


def recover_secret(shares):
    """Recovers the 32-byte secret from a set of shares using Lagrange interpolation."""
    if not shares:
        raise EscrowError('No shares provided')
    threshold = shares[0]['threshold']
    fingerprint = shares[0]['fingerprint']

    if len(shares) < threshold:
        raise EscrowError(f'Insufficient shares: got {len(shares)}, required {threshold}')

    # Validate distinct indices and matching threshold/fingerprint
    used_shares = shares[:threshold]
    seen_indices = set()
    points = []
    for s in used_shares:
        idx = s['index']
        if idx in seen_indices:
            raise EscrowError(f'Duplicate custodian share index: {idx}')
        if s['threshold'] != threshold or s['fingerprint'] != fingerprint:
            raise EscrowError('Incompatible shares from different key generations')
        seen_indices.add(idx)
        val = int(s['share'], 16)
        points.append((idx, val))

    # Lagrange interpolation at x = 0
    secret_int = 0
    for j, (xj, yj) in enumerate(points):
        numerator = 1
        denominator = 1
        for m, (xm, _) in enumerate(points):
            if m == j:
                continue
            numerator = (numerator * (-xm)) % FIELD_PRIME
            denominator = (denominator * (xj - xm)) % FIELD_PRIME

        lagrange_weight = (numerator * mod_inverse(denominator, FIELD_PRIME)) % FIELD_PRIME
        term = (yj * lagrange_weight) % FIELD_PRIME
        secret_int = (secret_int + term) % FIELD_PRIME

    secret_bytes = secret_int.to_bytes(32, byteorder='big')
    if hashlib.sha256(secret_bytes).hexdigest() != fingerprint:
        raise EscrowError('Reconstructed key failed fingerprint integrity verification')

    return secret_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)

    split_p = subparsers.add_parser('split', help='Split a backup key into custodian escrow shares')
    split_p.add_argument('--key-file', required=True, help='Path to master 32-byte key file')
    split_p.add_argument('--output-dir', required=True, help='Directory to write share files')
    split_p.add_argument('--total', type=int, default=3, help='Total custodian shares (N)')
    split_p.add_argument('--threshold', type=int, default=2, help='Reconstruction threshold (K)')

    combine_p = subparsers.add_parser('combine', help='Combine shares to recover master key')
    combine_p.add_argument('--shares-dir', required=True, help='Directory containing share JSON files')
    combine_p.add_argument('--output-key', required=True, help='Path to write recovered 32-byte key')

    verify_p = subparsers.add_parser('verify', help='Verify that escrow shares can reconstruct key')
    verify_p.add_argument('--shares-dir', required=True, help='Directory containing share JSON files')

    args = parser.parse_args()
    try:
        if args.command == 'split':
            key_data = Path(args.key_file).read_bytes()
            shares = split_secret(key_data, n=args.total, k=args.threshold)
            out = Path(args.output_dir)
            out.mkdir(parents=True, exist_ok=True, mode=0o700)
            for s in shares:
                share_path = out / f"custodian_share_{s['index']}_of_{args.total}.json"
                share_path.write_text(json.dumps(s, indent=2))
            print(json.dumps({'status': 'ESCROW_SHARES_CREATED', 'threshold': args.threshold, 'total': args.total}))
        elif args.command == 'combine':
            shares = []
            for p in sorted(Path(args.shares_dir).glob('*.json')):
                try:
                    shares.append(json.loads(p.read_text()))
                except Exception:
                    pass
            key_bytes = recover_secret(shares)
            out_path = Path(args.output_key)
            with open(os.open(out_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as f:
                f.write(key_bytes)
            print(json.dumps({'status': 'KEY_RECONSTRUCTED', 'fingerprint': hashlib.sha256(key_bytes).hexdigest()}))
        elif args.command == 'verify':
            shares = []
            for p in sorted(Path(args.shares_dir).glob('*.json')):
                try:
                    shares.append(json.loads(p.read_text()))
                except Exception:
                    pass
            key_bytes = recover_secret(shares)
            print(json.dumps({'status': 'ESCROW_VERIFIED', 'fingerprint': hashlib.sha256(key_bytes).hexdigest()}))
        return 0
    except Exception as err:
        print(f"Key escrow error: {err}", file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
