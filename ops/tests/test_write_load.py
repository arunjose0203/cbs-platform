"""P-06: the write-path driver against a fake CBS that applies the bank-version and posting rules."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import sys
import threading
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'perf'))
import write_load


class FakeCbs:
    """Bank version bumps on every approval; a request made against an older version becomes STALE when approved."""
    def __init__(self, lose_holds=False):
        self.lock = threading.Lock()
        self.version = 1
        self.requests = {}
        self.posted = {}      # account -> list of entries
        self.held = {}
        self.lose_holds = lose_holds


def handler_for(fake):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            parts = self.path.split('?')[0].split('/')
            with fake.lock:
                if self.path.endswith('/settings'):
                    return self.reply(200, {'businessDate': '2026-11-14'})
                if '/account-operations/' in self.path:
                    account = parts[-1]
                    items = [{'entry': {'id': rid}, 'status': 'POSTED', 'failureCode': None} for rid in fake.posted.get(account, [])]
                    held = fake.held.get(account, 0)
                    return self.reply(200, {'items': list(reversed(items)), 'position': {'reconciled': True, 'held': str(held)}})
                return self.reply(200, {'version': fake.version})

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            with fake.lock:
                if self.path.endswith('/account-operations'):
                    if body['expectedVersion'] != fake.version:
                        return self.reply(409, {'code': 'STALE_VERSION'})
                    rid = str(uuid.uuid4())
                    fake.requests[rid] = body
                    return self.reply(202, {'id': rid})
                rid = self.path.split('/')[-2]
                request = fake.requests[rid]
                if request['expectedVersion'] != fake.version:
                    return self.reply(200, {'id': rid, 'status': 'STALE'})
                fake.version += 1
                account = request['accountId']
                fake.posted.setdefault(account, []).append(rid)
                if request['kind'] == 'HOLD':
                    fake.held[account] = fake.held.get(account, 0) + 1
                elif not fake.lose_holds:
                    fake.held[account] -= 1
                return self.reply(200, {'id': rid, 'status': 'APPROVED'})

        def log_message(self, *args):
            pass
    return Handler


class WriteLoadTests(unittest.TestCase):
    def serve(self, fake):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(fake))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return f'http://127.0.0.1:{server.server_address[1]}'

    def test_concurrent_makers_go_stale_resubmit_and_every_cycle_posts(self):
        fake = FakeCbs()
        summary = write_load.run(self.serve(fake), 'bank', ['a1', 'a2', 'a3'], 3, 'maker', 'checker')
        self.assertEqual(summary['posted'], 18)
        self.assertEqual(summary['expectedPostings'], 18)
        self.assertTrue(summary['booksReconciled'])
        self.assertTrue(summary['nothingLeftHeld'])
        self.assertEqual(summary['failures'], {})
        self.assertEqual(write_load.verdict(summary), [])
        self.assertIsNotNone(summary['p95Ms'])

    def test_holds_left_behind_are_a_balance_error(self):
        fake = FakeCbs(lose_holds=True)
        summary = write_load.run(self.serve(fake), 'bank', ['a1'], 2, 'maker', 'checker')
        self.assertFalse(summary['nothingLeftHeld'])
        self.assertIn('balance error: accounts not reconciled or holds left behind', write_load.verdict(summary))

    def test_thresholds(self):
        base = {'posted': 4, 'expectedPostings': 4, 'failures': {}, 'booksReconciled': True, 'nothingLeftHeld': True, 'p95Ms': 900, 'postingsPerSecond': 2.0}
        self.assertEqual(write_load.verdict(base, p95_ms=1000, min_tps=1), [])
        self.assertEqual(len(write_load.verdict(base, p95_ms=500, min_tps=5)), 2)
        self.assertIn('not every operation posted', write_load.verdict({**base, 'posted': 3}))

    def test_requires_two_different_identities(self):
        with self.assertRaises(SystemExit):
            write_load.main(['http://127.0.0.1:1', '--bank', 'b', '--accounts', 'missing.json'])


if __name__ == '__main__':
    unittest.main()
