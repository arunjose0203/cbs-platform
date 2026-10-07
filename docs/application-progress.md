# CBS application progress — 7 October 2026

## Overall position

The application has an implemented administration and accounting foundation and an
initial deposit-product catalogue, individual customer onboarding and deposit account opening/funding with customer balances. It is a development system, not a complete
production CBS. Approved received-deposit credits are available. Holds/releases, recorded withdrawals, same-currency internal transfers, full funding reversals and customer statement views have now been added. Counting modules would give a misleading completion percentage:
the remaining financial and operational modules differ greatly in size.

The stack is Java 21, Spring Boot, PostgreSQL on Neon for development, and a browser
administration portal with local Keycloak identity integration. Shared bank
configuration lives in the control database; ledger/journal records are isolated
in each bank's dedicated database. Product definitions use the control database.

## Module status

| Module | Implemented capability | Remaining scope / readiness |
| --- | --- | --- |
| 00 Foundation | Build, packaging, health, migration infrastructure, CI workflow | GitHub CI execution, hosted deployment and recovery not verified |
| 01 Bank administration | Bank registration, legal entities, database binding/provisioning, lifecycle and audit | Live operational acceptance and backup/restore drills |
| 02 Staff and access | Staff identities, bank/branch grants, MFA checks, sessions, local identity provider | Restore working MFA for normal use; production identity setup and access review |
| 03 Approval Inbox | Independent approval, rejection, cancellation, history, stale-request and retry protection | Broader role/limit-based approval policies |
| 04 Reference data | Currency, language, rounding rules, business calendar and settings approval | Country-specific policy setup and acceptance |
| 05.1 Chart of Accounts | Hierarchy, categories, posting definitions and approvals | Bank-specific accounting/regulatory chart setup |
| 05.2 Ledger Accounts | Per-bank ledger storage, approval delivery and retry recovery | Customer subledgers and holds |
| 05.3 Journals | Balanced single-currency manual journals, full reversal, balances and immutable records | Customer transaction orchestration, FX transactions |
| 05.4 Reports | Trial balance, ledger statements, turnover reconciliation and CSV | Consolidated FX, regulatory and customer reports |
| 05.5 Business Day Close | Readiness checks, independent approval, date locks, next-working-day advancement, totals and recovery | Full end-of-day batches, period/year closing and controlled reopening |
| 06.1 Product Factory | Savings/current definitions, currency, minimum rules, liability mapping, activation approval and revisions | Interest, fees, eligibility, overdrafts and effective-date policies |
| 07 Customer onboarding | Individual profiles, document/manual-review references, duplicate checks, status approval and recoverable bank storage | Automated verification/screening, corporate customers, guardians, periodic review and privacy controls |
| 08.1–08.2 Accounts and deposits | Approved opening/funding, unique numbering, pinned terms, exact customer balances, dedicated liability ledger, atomic journal integration and retry recovery | Holds/releases, recorded withdrawals and full funding reversals implemented; limits, funded-account closure and broader corrections remain |
| 09 Internal transfers | Initial same-bank/entity/currency transfers, atomic posting and retry safety | Bank/branch permissions, amount limits and transfer reversals |
| 10 Teller and cash | Not implemented | Teller tills, cash deposits/withdrawals, limits, balancing and shortages |
| 11 Interest, fees, accruals | Not implemented | Calculation rules, schedules, accrual/posting, reversals and reconciliation |
| 12 Business-day processing | Accounting close foundation only | Scheduled financial batches, restart checkpoints and dependency sequencing |
| 13 Statements/notifications | Customer statement view with opening, closing and paginated running balances | Exports, delivery preferences, templates and delivery tracking |
| 14 External payments | Not implemented | Payment providers/rails, outbound/inbound states, exceptions and returns |
| 15 Settlement | Ledger reconciliation only | External statements, matching, breaks, settlement and exception queues |
| 16 FX | Multiple currency definitions and separated balances only | Exchange rates, conversion, revaluation, FX gains/losses |
| 17 Term deposits | Not implemented | Tenor, maturity, renewal, premature closure and interest |
| 18 Lending | Not implemented | Loan products, assessment workflow, schedules, repayment and arrears |
| 19 Reporting expansion | Accounting reports only | Operational dashboards, customer and jurisdiction-specific regulatory reporting |
| 20 Operational readiness | Some engineering controls and tests | Data migration, security/volume tests, backups, disaster recovery, monitoring, runbooks and pilot acceptance |

Implemented does not mean production-approved. English/French/Swahili interface
labels exist, but a full translation review and translated product/customer content
remain outstanding. Multi-currency currently means separated same-currency
products, ledgers and journals; it does not include currency conversion.

## End-to-end flow

Available development flow:

Bank registration → dedicated database preparation → staff access → approved bank
settings → approved chart/ledgers → manual journal approval/posting → accounting
reports/reconciliation → approved business-day close.

New configuration flow:

Enabled currency + approved liability chart definition → product request → second
administrator approval → active/inactive product with an immutable revision.

Customer journey (opening and received-deposit funding available; transfers/withdrawals onward remain planned):

Approved individual onboarding with manual review references (07) → approved active product (06) → customer
account opening with a pinned product revision and approved funding (08) → withdrawal or transfer
(09/10) → journal posting and reconciliation (05) → daily processing (11/12) →
customer statements (13).

Product creation currently stops at configuration. It does not create a customer
account, accept money, enforce withdrawal minimums or calculate charges.

## What to do next

1. Resolve sign-in: the previously authorized password-only exception has expired.
   It was not extended by module development. Restore MFA or explicitly authorize
   a new time-limited development exception.
2. With two different registered staff identities, test product creation,
   approval, activation, deactivation and revision history on a demo bank. Choose
   an enabled currency and active credit-normal liability posting definition.
3. Test **Module 07: Customer Onboarding** with fictional profiles, duplicate
   documents, independent activation approval and bank delivery. Define country-
   specific KYC requirements and future automated checks before a live pilot.
4. Test **Module 08.1: Deposit account opening** with an active delivered customer and product. Prepare account storage, submit and independently approve opening; verify the pinned terms and delivery. Test **08.2 funding** using fictional receipts and matching approved ledgers; verify customer/GL reconciliation and failure recovery. See [funding guide](module-08-funding.md).
5. Account operations now add funding corrections, holds, recorded withdrawals and internal transfers. Complete scoped operational permissions/limits, then teller/cash, interest/fees and full day-processing batches.
6. Review and commit the accumulated local changes before establishing a shared
   release baseline. The latest module work is currently uncommitted in this workspace.

## Verification and limits

Latest full local suite: Previous accepted baseline: **89 backend tests and 26 UI tests passed**. Current verification is recorded below. Backend
integration tests use disposable local PostgreSQL databases, never Neon customer
data. Browser demonstrations use an isolated fixture server with fictional data.
Control schema V13 adds funding jobs and dedicated account-ledger bindings; bank schema V7 adds immutable funding and journal/subledger constraints. Existing banks need Prepare funding storage.

No live customer, customer account, product or financial transaction was created during this
increment. End-to-end acceptance with real staff identities, hosted CI, realistic
load testing, disaster recovery and regulatory review remain outstanding.

## 7 October verified increment

Delivered approved holds/releases, recorded withdrawals, full funding reversals, same-bank/entity/currency transfers and customer statement views. See [account operations](module-08-operations.md). Production-profile startup guards were added, and the portal stylesheet authorization was fixed.

Full regression: 95 backend tests passed (zero failures/errors/skips); 30 UI tests passed. Following final migration-index and production-URL validation changes, the six affected integration/security tests passed again. Packaging succeeded. Browser checks used isolated fictional fixtures; no live customer transactions were posted. Neon control migration V14 applied and runtime readiness is UP; bank V8 remains an explicit storage-preparation step.

The complete production request is **not finished**. Teller/cash, interest/fees, financial batches, external payments, settlement, FX, term deposits, lending, operational permissions/limits and release acceptance remain. Target region is Africa; first jurisdiction, providers, deployment and recovery targets remain unspecified. See [production release gates](production-readiness.md). The 6 October development MFA exception expired at 22:17 IST and was not renewed by this development work.

## 7 October 2026: transaction-limit increment

- Added approved bank-wide currency/operation caps and control migration V15.
- Existing maker/checker separation and version checks govern policy changes.
- Submission and approval enforce caps; approved pending/failed postings remain in daily consumption.
- Added bank-detail policy form and statement CSV download button.
- Backend regression suite: 97 tests passed against isolated local PostgreSQL; initial packaging was blocked by the running Windows JAR lock, then retried after stopping the workspace application.
- UI regression suite: 30 tests passed. Statement export button verified with fictional browser fixtures.
- Branch/staff limits, operational staff financial access and the subsequent teller/processing/payment/lending modules remain outstanding. Provider selection and bank-approved limits were explicitly deferred by the user.

## 7 October 2026: branch operations increment

- Added approved account-to-branch ownership and branch/staff monetary policies (control migration V16).
- Added a Branch workspace with account listings, hold/release/within-branch transfer submission, scoped request review and decisions.
- Required recent MFA for operational routes, active branch and bank roles, independent manager approval and immutable original branch/staff attribution.
- Required both branch and staff limits for branch-bound operations, in addition to any configured bank caps. Existing unassigned development accounts retain the administrator workflow.
- Added administrator Branch controls for assignment and limit proposals.
- Full backend suite: 98 passed. UI suite: 33 passed. Fictional branch browser form submitted successfully.
- Not completed: teller till/vault custody, branch funding, cash denominations and reconciliation, interest/fees/accrual processing, notifications, provider integrations, FX, term deposits, lending and production acceptance. See branch-operations.md for the exact supported scope.

## 2026-10-07: mandatory transaction policies

Completed the first limit-hardening increment in the requested release sequence. Funding, transfers, withdrawals, funding reversals and holds now fail closed when their bank/currency/operation policy is missing. Hold release remains exempt. Pending policy requests grant no authority. The administration dialog lists missing policies for enabled currencies. No live bank limits were seeded.

Validation: 98 backend tests passed on isolated local PostgreSQL; 34 UI tests passed. A subsequent focused integration test passed for missing-policy discovery, funding rejection with absent/pending policy, and acceptance after independent approval. UI bundle and application package built successfully.

This does not complete stage 1. Vault/till custody, denomination inventory, teller opening/closing, cash transfers, cash-funded deposits/withdrawals, shortage/overage handling and reconciliation remain pending. Stages 2–8 of the requested sequence remain outstanding; no production-completion claim is made.

## 2026-10-07: teller cash custody and customer cash

Added vault/till registration, external cash receipt/dispatch, same-branch vault/till transfers, counted opening/closing, daily counts, customer cash deposits and withdrawals. All financial components post atomically with receipt-based retry. Mandatory bank/branch/staff caps, current branch roles, maker/checker approval, customer eligibility and available-balance rules apply. Cash-backed ledgers reject bypass through general journals or the external funding/payout workflows. Daily processing blocks pending cash jobs, open tills and missing/outdated counts.

See teller-cash-management.md for setup, endpoints, accounting and limitations. This completes a substantial teller increment, not the whole eight-stage plan. Cash exception/reversal workflows and production acceptance remain pending, followed by interest/fees/taxes and the later stages. No live financial limits, balances or cash locations were seeded.

### 7 October 2026 — cash exception investigation increment

Added branch-scoped discrepancy reporting, immutable physical count evidence, location freeze, day-close blocking and independent manager resolution of verified counting errors. Reporting and resolution preserve cash/customer/GL balances and have retry protection. The branch UI exposes reporting and case review. Control migration V18 is additive; bank schema stays V9.

This increment does not finish Teller & Cash Management. Actual shortage/overage accounting adjustments, reversals, inter-branch cash, denomination inventory and bank acceptance remain pending before advancing through the broader ordered roadmap.

### 7 October 2026 — approved cash shortage/overage adjustments

Added exact case-linked shortage/overage requests, approved expense/income ledger selection, bank/branch/staff caps, independent manager review excluding the case reporter, atomic cash/GL posting, unique case receipts and post-confirmation case resolution. Failed postings keep the location frozen; retry and lost-acknowledgement recovery are covered. Customer balances remain unchanged. Control schema V19 and bank schema V10 are required; existing banks need ownership-checked cash storage preparation. No live financial limits, chart mappings or adjustment transactions are seeded.

Next: cash/customer reversal workflows. Broader teller operations and production acceptance remain incomplete.

### 7 October 2026 — cash and customer transaction reversals

Added original-linked full cash reversals, withdrawal reversals and internal transfer reversals. Cash/customer/GL effects post atomically, duplicate and reversal-of-reversal requests are rejected, debit availability/holds/minimum balances and teller custody remain enforced, and branch/account screens expose scoped original selections. Control V20 and bank V11 add approved reversal limits and database receipt checks. No external recall or live transaction is initiated.

Remaining teller work includes inter-branch cash/in-transit, denomination inventory, receipt printing, emergency custody reassignment and adjustment case reopening. Production acceptance and later roadmap modules remain incomplete.

### 7 October 2026 — inter-branch cash in transit

Added same-bank/entity/currency vault dispatch, independently approved destination receipts and physical returns, denomination evidence, partial settlement, immutable shipment links, outstanding/overdue tracking and day-close blocking. Dedicated transit ledgers reject generic postings; atomic bank transactions and acknowledgement retries preserve cash and GL consistency. Control V21 / bank V12 are required.

Remaining teller work includes shipment-specific investigation/write-off workflows, carrier custody, denomination inventory, printable receipts, emergency custody reassignment and bank acceptance. Configurable overnight carry is not implemented; unresolved shipments block day close. Later roadmap modules and production gates remain outstanding. See [operator guide](interbranch-cash.md).
