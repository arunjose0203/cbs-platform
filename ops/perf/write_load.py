#!/usr/bin/env python3
"""Write-path load driver for a CBS staging deployment (P-06).

Each worker owns one account and repeats a balance-neutral cycle through the full maker-checker path:
submit a HOLD as the maker, approve it as the checker, wait until it is posted, then do the same for the
matching RELEASE. Afterwards every account must be reconciled with nothing left held, which is the
zero-balance-error check. Use it against staging, never production:

    CBS_MAKER_TOKEN=... CBS_CHECKER_TOKEN=... python3 ops/perf/write_load.py https://cbs-staging.example \
        --bank <bank-uuid> --accounts accounts.json --cycles 20 --p95-ms 2000

accounts.json is a JSON list of account UUIDs. Use one per worker; the bank allows one undelivered operation
per account. Tokens come from the environment so they never appear in shell history. Submissions carry the
bank version, so concurrent makers meet STALE_VERSION. Those retries are counted: they measure tenant-row
serialization. Exit status is 1 when a threshold is missed or the books do not reconcile.
"""
import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from load_probe import percentile  # noqa: E402


class Api:
    def __init__(self, base, token, timeout=15.0):
        self.base = base.rstrip('/') + '/api/v1'
        self.token = token
        self.timeout = timeout

    def call(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header('Authorization', 'Bearer ' + self.token)
        if data is not None:
            request.add_header('Content-Type', 'application/json')
        for k, v in (headers or {}).items():
            request.add_header(k, v)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except ValueError:
                return error.code, None


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.latencies = []      # submit -> posted, milliseconds
        self.stale = 0
        self.stale_at_approval = 0
        self.failures = {}
        self.posted = 0

    def add(self, ms):
        with self.lock:
            self.latencies.append(ms)
            self.posted += 1

    def fail(self, code):
        with self.lock:
            self.failures[code] = self.failures.get(code, 0) + 1

    def retry(self):
        with self.lock:
            self.stale += 1

    def went_stale(self):
        with self.lock:
            self.stale_at_approval += 1


def submit(maker, bank, account, kind, original, stats):
    """Submits one operation with the current bank version; returns the approval request ID or None."""
    for _ in range(50):
        _, bank_view = maker.call('GET', f'/admin/tenants/{bank}')
        _, settings = maker.call('GET', f'/admin/tenants/{bank}/settings')
        change = {'expectedVersion': bank_view['version'], 'kind': kind, 'accountId': account, 'destinationId': None,
                  'assetLedgerId': None, 'originalId': original, 'amount': '1', 'businessDate': settings['businessDate'],
                  'reference': 'LOAD-' + uuid.uuid4().hex[:12], 'reason': 'Staging write-path load test'}
        status, body = maker.call('POST', f'/admin/tenants/{bank}/account-operations', change, {'Idempotency-Key': str(uuid.uuid4())})
        if status == 409 and (body or {}).get('code') == 'STALE_VERSION':
            stats.retry()
            continue
        if status != 202:
            stats.fail((body or {}).get('code') or str(status))
            return None
        return body['id']
    stats.fail('STALE_VERSION_EXHAUSTED')
    return None


def post_one(maker, checker, bank, account, kind, original, stats, poll=0.25, wait=120.0):
    """Submits one operation, approves it and waits until it is posted. Returns the request ID, or None."""
    started = time.monotonic()
    for _attempt in range(20):
        request = submit(maker, bank, account, kind, original, stats)
        if request is None:
            return None
        status, body = checker.call('POST', f'/admin/approvals/{request}/approve', {'reason': 'Staging load test'})
        if status != 200:
            stats.fail((body or {}).get('code') or str(status))
            return None
        if (body or {}).get('status') == 'APPROVED':
            break
        # Another approval changed the bank version after this request was made: like staff, submit again.
        stats.went_stale()
    else:
        stats.fail('STALE_AT_APPROVAL_EXHAUSTED')
        return None
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        _, dashboard = maker.call('GET', f'/admin/tenants/{bank}/account-operations/{account}?limit=25&offset=0')
        item = next((i for i in (dashboard or {}).get('items', []) if i['entry']['id'] == request), None)
        if item and item['status'] == 'POSTED' and not item.get('failureCode'):
            stats.add((time.monotonic() - started) * 1000)
            return request
        if item and item.get('failureCode'):
            stats.fail(item['failureCode'])
            return None
        time.sleep(poll)
    stats.fail('NOT_POSTED_IN_TIME')
    return None


def run(base, bank, accounts, cycles, maker_token, checker_token):
    maker, checker, stats = Api(base, maker_token), Api(base, checker_token), Stats()

    def worker(account):
        for _ in range(cycles):
            hold = post_one(maker, checker, bank, account, 'HOLD', None, stats)
            if hold:
                post_one(maker, checker, bank, account, 'RELEASE', hold, stats)

    started = time.monotonic()
    threads = [threading.Thread(target=worker, args=(a,), daemon=True) for a in accounts]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.monotonic() - started
    positions = []
    for account in accounts:
        _, dashboard = maker.call('GET', f'/admin/tenants/{bank}/account-operations/{account}?limit=1&offset=0')
        positions.append((dashboard or {}).get('position') or {})
    return summarize(stats, elapsed, positions, len(accounts) * cycles * 2)


def summarize(stats, elapsed, positions, expected):
    latencies = sorted(stats.latencies)
    reconciled = all(p.get('reconciled') is True for p in positions) and len(positions) > 0
    nothing_held = all(str(p.get('held')) in ('0', '0.00') for p in positions)
    return {
        'expectedPostings': expected,
        'posted': stats.posted,
        'postingsPerSecond': round(stats.posted / elapsed, 2) if elapsed > 0 else None,
        'p50Ms': percentile(latencies, 0.50),
        'p95Ms': percentile(latencies, 0.95),
        'maxMs': latencies[-1] if latencies else None,
        'staleVersionRetries': stats.stale,
        'staleAtApproval': stats.stale_at_approval,
        'failures': stats.failures,
        'booksReconciled': reconciled,
        'nothingLeftHeld': nothing_held,
        'seconds': round(elapsed, 1),
    }


def verdict(summary, p95_ms=None, min_tps=None):
    problems = []
    if summary['posted'] != summary['expectedPostings'] or summary['failures']:
        problems.append('not every operation posted')
    if not summary['booksReconciled'] or not summary['nothingLeftHeld']:
        problems.append('balance error: accounts not reconciled or holds left behind')
    if p95_ms is not None and (summary['p95Ms'] is None or summary['p95Ms'] > p95_ms):
        problems.append(f'p95 above {p95_ms} ms')
    if min_tps is not None and (summary['postingsPerSecond'] or 0) < min_tps:
        problems.append(f'throughput below {min_tps} postings/s')
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('base')
    parser.add_argument('--bank', required=True)
    parser.add_argument('--accounts', required=True, help='JSON file with a list of account UUIDs')
    parser.add_argument('--cycles', type=int, default=10, help='hold/release cycles per account')
    parser.add_argument('--p95-ms', type=float)
    parser.add_argument('--min-tps', type=float)
    args = parser.parse_args(argv)
    maker, checker = os.environ.get('CBS_MAKER_TOKEN'), os.environ.get('CBS_CHECKER_TOKEN')
    if not maker or not checker or maker == checker:
        parser.error('set CBS_MAKER_TOKEN and CBS_CHECKER_TOKEN for two different staff identities')
    with open(args.accounts, encoding='utf-8') as f:
        accounts = json.load(f)
    if not isinstance(accounts, list) or not accounts or len(set(accounts)) != len(accounts):
        parser.error('accounts must be a non-empty list of distinct account IDs')
    summary = run(args.base, args.bank, accounts, args.cycles, maker, checker)
    problems = verdict(summary, args.p95_ms, args.min_tps)
    summary['problems'] = problems
    print(json.dumps(summary, indent=2))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
