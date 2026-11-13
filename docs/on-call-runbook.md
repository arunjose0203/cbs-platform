# CBS On-Call Operations Runbook (P-05, Gate E07)

Added 14 November 2026. Production-engineering item P-05 (gate E07) of the [go-live plan](go-live-plan-2026-11-13.md).

## Operational overview

This runbook guides on-call operations engineers responding to automated Prometheus alerts.
All alerts route to the primary operations pager. Critical alerts require immediate response ($\le 15$ minutes).

---

## Alert catalogue and triage procedures

### 1. `CbsApplicationUnavailable` (Critical)

- **Description:** CBS HTTP edge or internal readiness probe (`/health/ready`) is failing.
- **Triage steps:**
  1. Inspect container logs:
     ```bash
     docker compose -f deploy/staging/compose.yml logs --tail=100 app
     ```
  2. Check if the database is reachable:
     ```bash
     docker compose -f deploy/staging/compose.yml exec app psql -U cbs_runtime -d cbs_control -c "SELECT 1"
     ```
  3. Verify whether an offline migration was applied without reapplying runtime privileges (`ops/runtime-privileges.sql`).
  4. If database connections are exhausted, restart the application container cleanly:
     ```bash
     docker compose -f deploy/staging/compose.yml restart app
     ```

### 2. `CbsOperationalCheckFailed` (Critical)

- **Description:** `ops/monitor.py` has identified one or more failed internal checks (backup failure, stale restore evidence, database connectivity, or stuck postings).
- **Triage steps:**
  1. Inspect the monitor output file:
     ```bash
     cat /var/lib/cbs-monitor/cbs.prom
     ```
  2. If the alert is `BACKUP_STALE`:
     - Check systemd timer: `systemctl status cbs-backup.timer`
     - Manually run backup job: `python3 ops/backup_job.py --config /etc/cbs/backup.json`
  3. If the alert is `RESTORE_DRILL_STALE`:
     - A restore drill has not been verified within the configured window (default 7 days).
     - Execute the isolated restore drill in staging: `ops/tests/recovery_drill.py`.

### 3. `CbsDailyCycleBlocked` (Warning)

- **Description:** An active bank's scheduled daily batch cycle (servicing run, period close, or day close) could not proceed automatically.
- **Triage steps:**
  1. Log into the Administration Portal and navigate to **Operations console**.
  2. Review the **Day-close readiness** card to inspect specific blocking reasons:
     - Unposted manual journals or unacknowledged deliveries.
     - Open teller cash sessions or uncounted cash tills.
     - Open payment exceptions or unresolved returns.
     - Unapproved maker-checker items in the Approval Inbox.
  3. Resolve or dismiss the operational blockers.
  4. In the console, click **Prepare daily servicing batch** or **Request day close** once prerequisites are cleared.

### 4. `CbsReconciliationDifference` (Critical)

- **Description:** A non-zero difference detected between legacy and CBS trial balances, or subledger GL divergence.
- **Triage steps:**
  1. Navigate to **Data migration & cutover** or **Accounting Reports → Turnover reconciliation**.
  2. Run trial balance comparison by currency:
     ```bash
     curl -H "Authorization: Bearer $TOKEN" "https://$DOMAIN/api/v1/admin/tenants/$BANK/migration/reconcile"
     ```
  3. If during cutover: do NOT proceed with Go/No-Go until variance is exactly `0.00`.
  4. If an uncommitted batch caused the variance, trigger **Batch rollback** to invalidate bad records safely.

### 5. `CbsDeliveryFailures` & `CbsDeliveryQueueStuck` (Warning / Critical)

- **Description:** Background delivery workers encountered a delivery failure or a queue has unposted items older than 10 minutes.
- **Triage steps:**
  1. Check **Operations console → Delivery queues** to identify the failing queue (e.g., `journal_delivery`, `cash_delivery`, `contract_delivery`).
  2. Click **Retry stalled queues** or inspect worker logs:
     ```bash
     grep "remains pending; retry is safe" /var/log/cbs/app.log
     ```
  3. If bank database lock contention is high, verify that no long-running analytical query holds table locks.

### 6. `CbsComplianceCasesOverdue` (Warning)

- **Description:** Compliance screening cases have exceeded their assigned SLA investigator due date.
- **Triage steps:**
  1. In the portal, navigate to **Compliance cases** and filter by **Overdue**.
  2. Reassign overdue cases or escalate to the Chief Compliance Officer.

---

## Fault injection and drill procedure (Gate E07 Exit)

To prove observability under simulated failure conditions:
1. **Inject Delivery Failure:**
   - Temporarily pause target bank database or revoke delivery role access.
   - Observe `cbs_pending_age_seconds` climb above 600 seconds.
   - Verify `CbsDeliveryQueueStuck` alert fires in Prometheus and routes to pager.
   - Restore access, click **Retry stalled queues**, and verify the alert resolves automatically.
2. **Inject Reconciliation Difference:**
   - Stage a mock migration batch with a mismatched legacy trial balance entry.
   - Observe `cbs_reconciliation_difference_detected > 0` and verify `CbsReconciliationDifference` triggers.
   - Roll back the staged batch and verify alert clears.
