#!/usr/bin/env python3
"""Concurrent read-only load probe for a CBS deployment.

Sends GET requests to one URL from several workers for a fixed duration and prints latency
percentiles, throughput and the status-code mix as JSON. Use it against staging, never production:

    CBS_TOKEN=... python3 ops/perf/load_probe.py https://cbs-staging.example/api/v1/access/me \
        --workers 16 --seconds 60 --p95-ms 300

The bearer token is read from CBS_TOKEN so it never appears in shell history or process lists.
429 responses are counted separately: a probe from a single address will meet the per-client
rate limit by design, so raise cbs.rate-limit.requests-per-minute on staging for the run, or
spread the probe across addresses. Exit status is 1 when any threshold is missed.
"""
import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request


def percentile(sorted_values, fraction):
    if not sorted_values:
        return None
    index = min(len(sorted_values) - 1, max(0, round(fraction * (len(sorted_values) - 1))))
    return sorted_values[index]


def summarize(samples, elapsed):
    """samples: list of (status, milliseconds). Returns a JSON-ready summary."""
    latencies = sorted(ms for status, ms in samples if 200 <= status < 300)
    statuses = {}
    for status, _ in samples:
        statuses[str(status)] = statuses.get(str(status), 0) + 1
    ok = len(latencies)
    return {
        'requests': len(samples),
        'ok': ok,
        'rate_limited': statuses.get('429', 0),
        'errors': len(samples) - ok - statuses.get('429', 0),
        'throughput_per_second': round(ok / elapsed, 1) if elapsed > 0 else 0.0,
        'p50_ms': percentile(latencies, 0.50),
        'p95_ms': percentile(latencies, 0.95),
        'p99_ms': percentile(latencies, 0.99),
        'statuses': dict(sorted(statuses.items())),
    }


def verdict(summary, p95_ms=None, max_error_rate=0.0):
    problems = []
    if summary['requests'] == 0:
        problems.append('no requests completed')
    elif summary['errors'] / summary['requests'] > max_error_rate:
        problems.append(f"error rate {summary['errors']}/{summary['requests']} above {max_error_rate}")
    if p95_ms is not None and (summary['p95_ms'] is None or summary['p95_ms'] > p95_ms):
        problems.append(f"p95 {summary['p95_ms']} ms above {p95_ms} ms")
    return problems


def run(url, workers, seconds, token=None, timeout=10.0):
    deadline = time.monotonic() + seconds
    samples, lock = [], threading.Lock()
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token

    def worker():
        local = []
        while time.monotonic() < deadline:
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
                    response.read()
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            except (urllib.error.URLError, OSError):
                status = 0
            local.append((status, round((time.perf_counter() - started) * 1000, 2)))
        with lock:
            samples.extend(local)

    began = time.monotonic()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(workers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return summarize(samples, time.monotonic() - began)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('url')
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--seconds', type=float, default=30)
    parser.add_argument('--p95-ms', type=float)
    parser.add_argument('--max-error-rate', type=float, default=0.0)
    args = parser.parse_args(argv)
    if not args.url.startswith(('https://', 'http://127.0.0.1', 'http://localhost')):
        parser.error('use HTTPS, or plain HTTP only against localhost')
    summary = run(args.url, args.workers, args.seconds, os.environ.get('CBS_TOKEN'))
    problems = verdict(summary, args.p95_ms, args.max_error_rate)
    summary['status'] = 'FAIL' if problems else 'PASS'
    summary['problems'] = problems
    print(json.dumps(summary, indent=2))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
