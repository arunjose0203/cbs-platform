# Production Cutover & Data Migration Runbook (Round 19 / Gate E12)

## 1. Overview & Objectives
This runbook defines the operational protocol for migrating legacy banking data into the Core Banking System (CBS) and executing production cutover. It ensures zero data loss, strict accounting conservation (trial balance variance = 0.00), and a controlled rollback mechanism in compliance with prudential and regulatory standards.

---

## 2. Roles & Authority
| Role | Responsibility | Authority |
| --- | --- | --- |
| **Cutover Commander** | Overall execution lead, timeline adherence, coordination | Authorizes Go / No-Go decision |
| **Lead Migration DBA** | Database backup, offline migration execution (`MigrationMain`), privilege control | Manages DB snapshots and access grants |
| **Finance Reconciler** | Trial balance extracts, GL subledger verification, variance checks | Signs off financial reconciliation |
| **Compliance Officer** | Sanctions re-screening verification, KYC document linkage | Signs off KYC & screening readiness |
| **Operations Lead** | Branch communications, staff sign-in testing, customer alerts | Authorizes branch opening |

---

## 3. Cutover Timeline & Step-by-Step Procedure

```
[T-7 Days] Dry Run Rehearsal (Mock migration on staging)
    │
[T-1 Day]  Pre-Cutover Freeze & Legacy Final TB Extraction
    │
[T-0:00]   Legacy System Freeze & Full Database Backup
    │
[T+1:00]   Offline Schema Migration (MigrationMain)
    │
[T+2:00]   Customer Ingestion & Deduplication
    │
[T+3:00]   Deposit Account Ingestion & Opening Balance Journals
    │
[T+4:00]   Loan & Term-Deposit Portfolio Ingestion
    │
[T+5:00]   Trial Balance Reconciliation (Zero Variance Gate)
    │
[T+5:30]   ★ GO / NO-GO DECISION GATE ★
    ├── [GO] ──> Apply Runtime Privileges & Production Guard ──> Open Branches
    └── [NO-GO] > Trigger Rollback Protocol ───────────────────> Restore Legacy
```

### Phase A: Pre-Cutover (T-7 Days to T-1 Day)
1. **T-7 Days: Rehearsal Drill**
   - Execute dry-run migration using sanitized historical extracts on staging.
   - Verify all scripts, memory limits, and schema migrations.
2. **T-1 Day: Pre-Cutover Verification**
   - Notify branches and customers of the scheduled maintenance window.
   - Extract legacy trial balance as of close of business.
   - Generate baseline ledger hashes and customer count extracts.

### Phase B: Cutover Execution (T-0 Hours)
1. **Step 1: Legacy Transaction Freeze (T-0:00)**
   - Disable payment gateways, mobile channels, and teller transaction entry in the legacy system.
   - Confirm queue depth = 0.
2. **Step 2: Database Snapshots & Backups (T-0:30)**
   - Take full pg_dump / physical snapshot of control and bank tenant databases.
   - Label snapshot: `cbs_pre_cutover_YYYYMMDD_HHMM`.
3. **Step 3: Offline Schema Migration (T-1:00)**
   - Execute `MigrationMain` with restricted migration credentials:
     ```bash
     java -cp cbs-platform.jar com.cbs.operations.MigrationMain migration-plan.json
     ```
   - Verify all migrations (V1 through V54) apply cleanly.
4. **Step 4: Customer Ingestion & Validation (T-2:00)**
   - Upload customer extracts via `POST /api/v1/admin/tenants/{bank}/migration/customers/stage`.
   - Verify validation report: zero unresolvable errors or identity collisions.
   - Commit batch: `POST /api/v1/admin/tenants/{bank}/migration/customers/commit/{batchId}`.
5. **Step 5: Deposit Accounts & Opening Balances (T-3:00)**
   - Stage deposit accounts via `POST /api/v1/admin/tenants/{bank}/migration/accounts/stage`.
   - Commit batch to generate opening balance journals:
     - Debit: Migration Suspense Ledger (`EQUITY` / Clearing)
     - Credit: Customer Account Liability Ledgers
     - Verify Debit = Credit.
6. **Step 6: Credit & Term Deposit Portfolio (T-4:00)**
   - Ingest live loans with historical schedules, remaining principal, and IFRS 9 allowances.
   - Ingest active term deposits with accrued interest and agreed maturity dates.
7. **Step 7: Trial Balance Reconciliation (T-5:00)**
   - Run automated reconciliation: `POST /api/v1/admin/tenants/{bank}/migration/reconcile`.
   - Compare subledger totals against legacy trial balance.
   - **Requirement**: Difference must equal exactly **0.00** across all currencies.

---

## 4. Go / No-Go Decision Gate (T+5:30)

### Mandatory Go Criteria:
- [ ] Schema migration verified (V54 applied, checksums match).
- [ ] 100% of active legacy customers registered in CBS.
- [ ] 100% of accounts and contracts staged and committed.
- [ ] Trial balance difference = **0.00** across KES, USD, EUR, GBP.
- [ ] Dual-control approvals functioning cleanly.
- [ ] ProductionGuard validation passes (`cbs.privacy.field-key` secure).

### Rollback Triggers:
- Discrepancy > 0.00 in trial balance reconciliation that cannot be resolved within 30 minutes.
- Database migration failure or corruption.
- Ingestion failure exceeding 0.1% of customer records.

---

## 5. Rollback Protocol

If a No-Go decision is declared:
1. **Immediate Abort**: Halt all background migration jobs.
2. **Execute Batch Rollback**:
   - Call `POST /api/v1/admin/tenants/{bank}/migration/batches/{batchId}/rollback` for any uncommitted batches.
3. **Database Restoration**:
   - Terminate active CBS application connections.
   - Restore database from the T-0:30 snapshot `cbs_pre_cutover_YYYYMMDD_HHMM`.
4. **Legacy System Reactivation**:
   - Unfreeze legacy core banking database and restart transaction listeners.
   - Notify branch managers and payment gateway partners that maintenance has concluded under the rollback protocol.
5. **Post-Mortem**: Assemble cutover logs, validation error reports, and recon breakdown for root-cause analysis.
