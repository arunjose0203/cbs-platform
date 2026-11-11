"""Drives deploy/staging/up.sh with stub docker/curl/python executables and checks the call order."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'deploy' / 'staging' / 'up.sh'
STUB = '#!/bin/sh\necho "{name} $*" >> "$CALLS"\n{body}\n'


@unittest.skipIf(os.name == 'nt', 'POSIX shell script')
class StagingUpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.calls = self.root / 'calls'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = self.root / 'staging.env'
        self.env.write_text('CBS_DOMAIN=cbs.example.org\nCBS_READY_INTERVAL=0\nCBS_READY_ATTEMPTS=3\n')
        self.stub('docker')
        self.stub('preflight')
        self.stub('curl')

    def tearDown(self):
        self.temp.cleanup()

    def stub(self, name, body='exit 0'):
        path = self.bin / name
        path.write_text(STUB.format(name=name, body=body))
        path.chmod(0o755)

    def run_up(self, *args, hook=None):
        env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}', CALLS=str(self.calls),
                   CBS_OPS_PYTHON=str(self.bin / 'preflight'))
        if hook:
            self.stub('grants')
            with self.env.open('a') as f:
                f.write(f'CBS_PRIVILEGES_HOOK={self.bin / "grants"}\n')
        result = subprocess.run(['sh', str(SCRIPT), '--env-file', str(self.env), *args], env=env,
                                capture_output=True, text=True, timeout=30)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return result, calls

    def test_stops_after_migrations_until_grants_are_reapplied(self):
        result, calls = self.run_up()
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertTrue(calls[0].startswith('preflight ops/staging_preflight.py'))
        self.assertTrue(any('--profile tools run --rm migrate' in c for c in calls))
        self.assertFalse(any(' up -d' in c for c in calls))
        self.assertIn('runtime-privileges.sql', result.stderr)

    def test_full_bring_up_in_order(self):
        result, calls = self.run_up(hook=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        order = [next(i for i, c in enumerate(calls) if marker in c) for marker in
                 ('preflight', 'stop app', 'run --rm migrate', 'grants', 'up -d --wait app edge', 'curl')]
        self.assertEqual(order, sorted(order))
        self.assertIn('https://cbs.example.org/health/ready', calls[-1])
        self.assertIn('ready at https://cbs.example.org/admin/', result.stdout)

    def test_restart_without_migrations(self):
        result, calls = self.run_up('--skip-migrations')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any('migrate' in c for c in calls))

    def test_reports_when_readiness_never_arrives(self):
        self.stub('curl', 'exit 22')
        result, calls = self.run_up('--skip-migrations')
        self.assertEqual(result.returncode, 4)
        self.assertEqual(sum(c.startswith('curl') for c in calls), 3)
        self.assertIn('did not become ready', result.stderr)

    def test_failed_preflight_starts_nothing(self):
        self.stub('preflight', 'exit 1')
        result, calls = self.run_up()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c.startswith('docker') for c in calls))


if __name__ == '__main__':
    unittest.main()
