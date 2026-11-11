from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'perf'))
import load_probe


class Handler(BaseHTTPRequestHandler):
    calls = 0
    lock = threading.Lock()

    def do_GET(self):
        with Handler.lock:
            Handler.calls += 1
            limited = Handler.calls % 5 == 0
        authorized = self.headers.get('Authorization') == 'Bearer probe-token'
        status = 429 if limited else (200 if authorized else 401)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *args):
        pass


class LoadProbeTests(unittest.TestCase):
    def test_percentiles_and_status_mix(self):
        samples = [(200, float(ms)) for ms in range(1, 101)] + [(429, 1.0), (500, 2.0), (0, 3.0)]
        summary = load_probe.summarize(samples, 10.0)
        self.assertEqual(summary['ok'], 100)
        self.assertEqual(summary['rate_limited'], 1)
        self.assertEqual(summary['errors'], 2)
        self.assertEqual(summary['p50_ms'], 51.0)
        self.assertEqual(summary['p95_ms'], 95.0)
        self.assertEqual(summary['throughput_per_second'], 10.0)
        self.assertEqual(load_probe.verdict(summary, p95_ms=100, max_error_rate=0.05), [])
        self.assertTrue(load_probe.verdict(summary, p95_ms=50, max_error_rate=0.05))
        self.assertTrue(load_probe.verdict(summary, max_error_rate=0.0))
        self.assertEqual(load_probe.verdict(load_probe.summarize([], 1.0)), ['no requests completed'])

    def test_live_probe_sends_token_and_counts_throttling(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f'http://127.0.0.1:{server.server_address[1]}/api/v1/access/me'
            summary = load_probe.run(url, workers=3, seconds=0.5, token='probe-token')
            self.assertGreater(summary['ok'], 0)
            self.assertGreater(summary['rate_limited'], 0)
            self.assertEqual(summary['errors'], 0)
            unauthenticated = load_probe.run(url, workers=1, seconds=0.2)
            self.assertEqual(unauthenticated['ok'], 0)
            self.assertIn('401', unauthenticated['statuses'])
        finally:
            server.shutdown()

    def test_refuses_plain_http_to_remote_hosts(self):
        with self.assertRaises(SystemExit):
            load_probe.main(['http://cbs.example/api', '--seconds', '0'])


if __name__ == '__main__':
    unittest.main()
