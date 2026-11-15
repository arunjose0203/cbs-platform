"""Unit tests for the Cutover Dress Rehearsal runner (ops/dress_rehearsal.py)."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import sys
import threading
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dress_rehearsal as dr


class MockCbsServer(HTTPServer):
    def __init__(self, addr, handler_cls, fail_health=False, old_schema=False, recon_diff=False, reject_close=False):
        super().__init__(addr, handler_cls)
        self.fail_health = fail_health
        self.old_schema = old_schema
        self.recon_diff = recon_diff
        self.reject_close = reject_close
        self.day_close_submitted = False
        self.day_close_approved = False
        self.current_business_date = "2026-11-14"


def make_handler():
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Suppress console logging during tests

        def reply(self, status, body):
            raw = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            server: MockCbsServer = self.server
            if self.path == "/health/ready":
                if server.fail_health:
                    return self.reply(503, {"status": "DOWN"})
                return self.reply(200, {"status": "UP"})

            if "/admin/tenants/" in self.path:
                if self.path.endswith("/business-day"):
                    return self.reply(200, {
                        "businessDate": server.current_business_date,
                        "open": True,
                        "version": 1,
                    })
                if self.path.endswith("/period-close"):
                    return self.reply(200, {
                        "ready": True,
                        "currentPeriod": "2026-11",
                        "status": "OPEN",
                    })
                if self.path.endswith("/migration/batches"):
                    return self.reply(200, [{"id": str(uuid.uuid4()), "status": "COMMITTED"}])
                # Tenant detail
                schema_ver = "28" if server.old_schema else "29"
                return self.reply(200, {
                    "id": str(uuid.uuid4()),
                    "bankCode": "DEMO_KE",
                    "status": "ACTIVE",
                    "version": 1,
                    "databaseBinding": {"schemaVersion": schema_ver},
                })

            return self.reply(404, {"error": "Not Found"})

        def do_POST(self):
            server: MockCbsServer = self.server
            content_length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(content_length).decode("utf-8")) if content_length > 0 else {}

            if "/migration/reconcile" in self.path:
                if server.recon_diff:
                    return self.reply(200, {
                        "reconciled": False,
                        "totalVariance": "50000.00",
                        "currencyVariances": {"KES": "50000.00"},
                    })
                return self.reply(200, {
                    "reconciled": True,
                    "totalVariance": "0.00",
                    "currencyVariances": {"KES": "0.00"},
                })

            if self.path.endswith("/business-day/close"):
                server.day_close_submitted = True
                return self.reply(202, {
                    "id": "appr-close-001",
                    "status": "PENDING",
                    "action": "DAY_CLOSE",
                })

            if "/admin/approvals/" in self.path and self.path.endswith("/approve"):
                if server.reject_close:
                    return self.reply(403, {"code": "DUTY_NOT_PERMITTED"})
                server.day_close_approved = True
                server.current_business_date = "2026-11-15"
                return self.reply(200, {"id": "appr-close-001", "status": "APPROVED"})

            return self.reply(404, {"error": "Not Found"})

    return Handler


class DressRehearsalTests(unittest.TestCase):
    def setUp(self):
        self.server = None
        self.thread = None

    def tearDown(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        if self.thread:
            self.thread.join()

    def start_server(self, **kwargs):
        self.server = MockCbsServer(("127.0.0.1", 0), make_handler(), **kwargs)
        port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.daemon = True
        self.thread.start()
        return f"http://127.0.0.1:{port}"

    def test_successful_dress_rehearsal_run(self):
        url = self.start_server()
        runner = dr.DressRehearsalRunner(url, uuid.uuid4(), "maker-token", "checker-token")
        report = runner.run()

        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["goNoGoRecommendation"], "GO")
        self.assertEqual(report["controlSchema"], 56)
        self.assertEqual(report["bankSchema"], 29)
        self.assertTrue(self.server.day_close_submitted)
        self.assertTrue(self.server.day_close_approved)
        self.assertEqual(self.server.current_business_date, "2026-11-15")

        step_names = [s["step"] for s in report["steps"]]
        self.assertIn("HEALTH_CHECK", step_names)
        self.assertIn("VERIFY_TENANT", step_names)
        self.assertIn("MIGRATION_RECONCILE", step_names)
        self.assertIn("DAY_CLOSE", step_names)
        self.assertIn("PERIOD_CLOSE", step_names)
        self.assertIn("POST_CLOSE_RECONCILE", step_names)

    def test_rehearsal_aborts_on_unhealthy_instance(self):
        url = self.start_server(fail_health=True)
        runner = dr.DressRehearsalRunner(url, uuid.uuid4(), "maker-token", "checker-token")

        with self.assertRaises(dr.RehearsalError) as ctx:
            runner.run()

        self.assertEqual(ctx.exception.step, "HEALTH_CHECK")
        self.assertEqual(runner.report["status"], "FAILED")
        self.assertEqual(runner.report["goNoGoRecommendation"], "NO-GO (TRIGGER ROLLBACK)")

    def test_rehearsal_fails_on_schema_version_mismatch(self):
        url = self.start_server(old_schema=True)
        runner = dr.DressRehearsalRunner(url, uuid.uuid4(), "maker-token", "checker-token")

        with self.assertRaises(dr.RehearsalError) as ctx:
            runner.run()

        self.assertEqual(ctx.exception.step, "VERIFY_TENANT")
        self.assertIn("expected 29", ctx.exception.message)
        self.assertEqual(runner.report["status"], "FAILED")

    def test_rehearsal_triggers_rollback_on_trial_balance_variance(self):
        url = self.start_server(recon_diff=True)
        runner = dr.DressRehearsalRunner(url, uuid.uuid4(), "maker-token", "checker-token")

        with self.assertRaises(dr.RehearsalError) as ctx:
            runner.run()

        self.assertEqual(ctx.exception.step, "MIGRATION_RECONCILE")
        self.assertIn("difference detected", ctx.exception.message)
        self.assertEqual(runner.report["status"], "FAILED")
        self.assertEqual(runner.report["goNoGoRecommendation"], "NO-GO (TRIGGER ROLLBACK)")

    def test_rehearsal_fails_if_day_close_approval_rejected(self):
        url = self.start_server(reject_close=True)
        runner = dr.DressRehearsalRunner(url, uuid.uuid4(), "maker-token", "checker-token")

        with self.assertRaises(dr.RehearsalError) as ctx:
            runner.run()

        self.assertEqual(ctx.exception.step, "DAY_CLOSE_APPROVE")
        self.assertEqual(runner.report["status"], "FAILED")


if __name__ == "__main__":
    unittest.main()
