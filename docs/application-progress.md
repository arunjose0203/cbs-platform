# CBS application progress — 9 October 2026

## Current functional acceptance baseline

Use the [Functional Gap & Acceptance Matrix](functional-gap-acceptance-matrix-2026-10-09.md) for the current 34-area functional assessment, 34 proposed acceptance cases, source/test evidence, confirmed limits and prioritized completion work. It reconciles older historical pending lists below. No module has independent bank functional sign-off recorded; existing automated test results do not replace that acceptance.

## P-10: Security Hardening, Threat Model, and Pen-Test Readiness — 14 November 2026

Production-engineering item P-10 (gates E01, E03) of the [go-live plan](go-live-plan-2026-11-13.md). See [threat model and security review](threat-model-and-security-review.md).

- **STRIDE Threat Modeling.** Comprehensive threat analysis across all 5 architectural trust boundaries (edge proxy, Keycloak identity, core runtime, control database, and isolated tenant databases), covering spoofing, tampering, repudiation, information disclosure, denial-of-service, and elevation-of-privilege.
- **OWASP API Security Top 10.** Systematic hardening against BOLA/IDOR, broken authentication, unrestricted resource consumption, and SSRF.
- **Cryptographic & Dual-Control Architecture.** Strict double-entry conservation, Shamir's 2-of-3 secret sharing for backup keys, continuous WAL encryption, AES-256-GCM PII encryption at rest, and mandatory maker-checker dual controls with immutable audit logging.
- **External Penetration Testing Scenarios.** Detailed penetration testing rules of engagement, attack surface mapping, and concrete verification scenarios (tenant isolation drills, maker-checker bypass, JWT algorithm confusion, SQL/formula injection fuzzing, and concurrency/replay tests).
- **Verification.** 10 `ProductionGuardTest` unit tests, 19 Keycloak identity tests, 8 staging preflight tests, and comprehensive integration tests pass with zero critical or high vulnerabilities.

## P-03: production identity — 14 November 2026

Production-engineering item P-03 (gate E03) of the [go-live plan](go-live-plan-2026-11-13.md). No migration. See [production identity](production-identity.md).

- **Keycloak on PostgreSQL in staging.** A new `identity` service with its own database login, TLS-verified database transport and a fixed public hostname behind the Caddy edge. The edge exposes only the `cbs` realm; the admin console and master realm are reachable only through an SSH tunnel. `up.sh` fails if the realm is not reachable over HTTPS.
- **Realm configuration in the repository.** `cbs-production-realm.json` requires password plus OTP on every sign-in and OTP enrollment for new accounts. It sets a strong password policy, brute-force limits, lifetimes within CBS's limits and 90-day login and admin events. The portal client is PKCE-only with exact HTTPS URIs, and the claims mappers are identical to development. The operator service identity is disabled by default. Tests pin all of this.
- **Preflight and guard.** Preflight rejects an unpinned image, a shared or placeholder domain, an unverified or shared database login, weakening overrides, and a CBS runtime that trusts anything other than the staging realm. `ProductionGuard` also refuses JWKS from another host and the development realm.
- **Operations.** `ops/identity_admin.py` rotates signing keys: it adds the new key first, keeps old keys passive and retires them after a grace period. It also creates staff sign-in accounts with a temporary password, written only to a private file, and forces OTP enrollment. The migration job registers the first CBS administrator for an identity provider, offline and audited.
- **Still open.** Running it on the real staging host (P-02) and the A02 acceptance run with real people and devices.

## P-05: Observability, Prometheus/Grafana Dashboards, and On-Call Runbooks — 14 November 2026

Production-engineering item P-05 (gate E07) of the [go-live plan](go-live-plan-2026-11-13.md). See [on-call runbook](on-call-runbook.md).

- **Prometheus alert rules.** Extended `deploy/staging/alerts.yml` with alerts for reconciliation differences (`CbsReconciliationDifference`), delivery failure spikes (`CbsDeliveryFailures`), and stuck delivery queues (`CbsDeliveryQueueStuck`).
- **Production metric instrumentation.** `ops/monitor.py` exports `cbs_reconciliation_difference_detected` (migration & subledger trial balance discrepancies) and `cbs_delivery_failures_24h` (failed bank delivery queue attempts in 24h).
- **Grafana dashboards.** Created production dashboards in `deploy/observability/dashboards/`:
  - `core-banking-overview.json`: Readiness, alert status, blocked cycles, reconciliation differences, and queue latency heatmaps.
  - `operations-and-daily-cycle.json`: Compliance SLA overdues, delivery failure rates, backup/restore freshness, and database connectivity.
- **On-call runbook.** `docs/on-call-runbook.md` provides operational procedures for triage, impact analysis, resolution steps, escalation paths, and fault injection verification drills for all platform alerts.
- **Verification.** 7 ops monitor unit tests pass (100%), 141 UI tests pass, and alerts syntax validated.

## Round 19: Data migration and cutover tooling — 14 November 2026

Round 19 of the [go-live plan](go-live-plan-2026-11-13.md) delivers production cutover tooling, staging loaders, deduplication, and trial balance reconciliation (Gate E12). Control migration **V54**. See [cutover runbook](cutover-runbook.md).

- **Staged data loaders.** Batched loaders for legacy customers, deposit accounts, live loan schedules, and term deposits. Deduplicates national IDs, verifies currency enablement, and rejects negative balances.
- **Atomic opening journals.** Migration opening balance journals post atomically against the migration clearing suspense ledger, preserving balance sheet conservation.
- **Trial balance reconciliation.** Automated reconciliation comparing migrated subledger positions against legacy closing trial balances, enforcing exact 0.00 variance across all supported currencies.
- **Batch rollback controls.** Controlled rollback mechanism (`ROLLBACK`) marking uncommitted migration items invalid and safe to re-stage without leaving orphaned records.
- **Production cutover runbook.** Complete operational runbook from T-7 rehearsal through T-0 freeze, schema migration (`MigrationMain`), ingestion, reconciliation gates, Go/No-Go decision rules, and emergency rollback protocol.
- **UI and verification.** Added Data Migration & Cutover UI view with batch history, batch rollback, and trial balance reconciliation. Tests: 3 new migration unit tests in `MigrationServiceTest` (100% pass), BankDutyRoutesTest updated for `/migration` duty mapping (100% pass), 138 UI tests pass.

## Round 16: FX and term-deposit completion — 14 November 2026

Round 16 of the [go-live plan](go-live-plan-2026-11-13.md) completes foreign exchange (FX) and term-deposit servicing (Matrix rows F26, F27, F28; Acceptance cases A26, A27, A28). Control migrations **V53** (platform) and **V29** (bank). See [fx and term-deposit completion](fx-and-term-deposit-completion.md).

- **Partial term-deposit closure.** `PARTIAL_CLOSE` allows partial principal withdrawals with early-closure penalty and withholding tax deduction, keeping remaining principal active and accruing interest until contractual maturity. Enforced under `TD_PARTIAL_CLOSE` limits.
- **Standing renewal instructions.** Registers and maintains automated actions upon maturity (`AUTOMATIC_RENEWAL_CAPITALIZE`, `AUTOMATIC_RENEWAL_PAYOUT`, `TRANSFER_TO_ACCOUNT_AT_MATURITY`) in `platform.term_standing_instruction`.
- **Cross exchange rate derivation.** Mathematically derived cross rates between two non-base foreign currencies via the bank's functional pivot currency, compounding bid/ask spreads strictly ($Buy = b_1 / s_2, Mid = m_1 / m_2, Sell = s_1 / b_2$).
- **External rate feeds.** Staging and maker-checker approval for external rate feeds (Central Bank of Kenya, Reuters) before activating them into the live rate catalogue (`platform.fx_rate_feed`).
- **FX valuation corrections.** Reverses prior erroneous valuation runs on the open business date with balanced reversing journal entries (`FXVC-`), unsealing the trading book in `platform.fx_valuation_correction`.
- **Full-bank monetary item translation (IAS 21).** Evaluates and translates all foreign-currency denominated balance sheet assets and liabilities at closing spot rates, aggregating total foreign assets, liabilities, and net translation gain/loss exposure.
- **UI and verification.** Added Partial Closure and Standing Order to Contracts UI; added Cross Rate and Rate Feeds to FX UI; added Valuation Correction and IAS 21 Translation to FX Valuation UI. Tests: 5 new backend integration tests (100% pass), 270 total backend tests pass, 137 UI tests pass.

## Round 15: Lending completion — 14 November 2026

Round 15 of the [go-live plan](go-live-plan-2026-11-13.md) completes the lending and loan servicing lifecycle (Matrix rows F30, F31, F32; Acceptance cases A30, A31, A32). Control migrations **V52** (platform) and **V28** (bank). See [lending completion](lending-completion.md).

- **Credit bureau inquiry and reporting (CRB).** Multi-bureau integration model (Metropol, TransUnion, Creditinfo) with deterministic credit scoring (300–850) and risk grading (AA to E). Generates statutory monthly portfolio returns classifying loans under CBK/IFRS 9 prudential asset classifications (`PERFORMING`, `WATCH`, `SUBSTANDARD`, `DOUBTFUL`, `LOSS`).
- **Non-accrual accounting.** Automatically suspends interest accrual into P&L for Stage 3 non-performing loans (>90 DPD), advancing contract accrual dates without inflating balance sheet interest receivables or income.
- **Loan moratoria.** Granular moratorium grants (`FULL_PAYMENT_HOLIDAY` and `INTEREST_ONLY`) with role authority checks. Arrears calculation automatically freezes ageing on instalments covered by an active moratorium.
- **Partial write-off & post-write-off recovery.** `PARTIAL_WRITE_OFF` writes down distressed principal against loan loss allowances without terminating the facility. `RECOVERY` handles post-write-off cash collections credited directly to recovery income ledgers under dual-control limits (`LOAN_RECOVERY`).
- **Automated IFRS 9 ECL staging.** Portfolio-wide ECL evaluation categorizing loans into Stage 1 (1.00%), Stage 2 (3.00%), and Stage 3 (20.00%) with automated required allowance and adjustment reporting.
- **Fee taxation and fee-payment reversal.** Automated 20% excise duty on assessed loan fees routed to dedicated tax ledgers. Enabled reversal of fee payments (`Kind.FEE_PAY`) with restored balances and dual-control limits (`LOAN_FEE_REVERSAL`).
- **Arrears snapshots & KYC document verification.** Arrears position snapshots (`platform.loan_arrears_snapshot`) eliminate 10,000-event replay boundaries. Collateral document verification links directly to KYC storage (`platform.customer_document`).
- **UI and verification.** Added Bureau Inquiry and CRB Report to Credit UI; added ECL Staging and Moratorium actions to Contracts UI. Tests: 7 new comprehensive backend integration tests in `LendingCompletionTest` (100% pass), 265 backend tests pass, 132 UI tests pass.

## Round 14: Regulatory and operational reporting — 14 November 2026

Round 14 of the [go-live plan](go-live-plan-2026-11-13.md) delivers comprehensive operational, regulatory, and statutory tax reporting (Matrix rows F09, F19). See [regulatory and operational reporting](regulatory-and-operational-reporting.md).

- **Deposit register.** Complete register of active customer deposit accounts with balances, product terms, and branch custody.
- **Loan register with DPD & NPL classification.** Portfolio register evaluating Days Past Due (DPD) and overdue instalments using `LoanArrears.calculate`. Classifies loans into performing and NPL (>90 DPD) under CBK guidelines.
- **Cash custody report.** Summarizes physical vault, teller till, and ATM custody holdings across branches with currency breakdown and verification statuses.
- **Tax remittance report (KRA WHT & Excise).** Calculates statutory Kenya Revenue Authority obligations across financial servicing runs: 15% Withholding Tax (WHT) on credited deposit interest, and 20% excise duty on assessed transactional fees.
- **Exception ageing & surveillance.** Aggregates open operational discrepancies across cash shortages, payment failures, and compliance alerts into statutory ageing buckets (`0–7d`, `8–30d`, `31–60d`, `61+d`).
- **Loan provisions & IFRS 9 staging.** Implements CBK/IFRS 9 staging: Stage 1 Performing (1% general provision), Stage 2 Underperforming (3% watch provision), Stage 3 Non-Performing (20% to 100% recommended provision).
- **Multi-format export.** Integrated into `TableOutput` supporting formatted JSON, sanitized CSV (formula injection neutralized), and A4 PDF.
- **UI and verification.** Added Operational reports dialog to management reports UI supporting report selection and downloads. Tests: 6 new backend unit tests in `OperationalReportServiceTest` (100% pass), 129 UI tests pass.

## P-04: Point-In-Time Recovery (PITR), WAL Archiving, Key Escrow and Off-Host Retention — 14 November 2026

Production-engineering item P-04 (gate E06) of the [go-live plan](go-live-plan-2026-11-13.md). See [PITR backup and recovery](pitr-backup-and-recovery.md).

- **Continuous WAL archiving.** `ops/wal_archive.py` encrypts and streams PostgreSQL WAL segments (`archive_command`) via AES-256-GCM with SHA-256 checksums and HMAC metadata envelopes. RPO is bounded to $\le 5$ minutes by setting `archive_timeout = 300`.
- **Point-In-Time Recovery (PITR).** Automated decryption and restore of requested WAL segments (`restore_command`), with generator for PostgreSQL `recovery_target_time` and promotion configurations.
- **M-of-N key escrow.** `ops/key_escrow.py` implements Shamir's threshold secret sharing over a 256-bit prime field, splitting the 32-byte master key into 2-of-3 custodian shares across Bank Risk, Engineering, and Audit.
- **Off-host transport & statutory retention.** `ops/offhost_sync.py` verifies remote immutable receipt HMACs and enforces retention (30 days daily, 12 weeks weekly, 7 years month-end) with the invariant of never deleting the newest completed baseline.
- **Verification.** 10 new unit tests across `test_wal_archive.py`, `test_key_escrow.py`, and `test_offhost_sync.py` (100% pass, 37 total ops unit tests).

## P-06: write-path performance and delivery drain — 14 November 2026

Production-engineering item P-06 (gate E08) of the [go-live plan](go-live-plan-2026-11-13.md). See [write-path performance](write-path-performance.md).

- **Delivery queue drain.** `LedgerDeliveryWorker.drain` delivers money queues in batches within a time budget (`cbs.delivery.drain-ms`, default 20s), instead of delivering only 20 items per 30-second interval. Failed items move to the back of the queue; if an item has already been attempted in the current pass, the pass terminates without spinning.
- **Configurable delivery interval.** `cbs.delivery.interval-ms` (default 30s) controls delivery worker polling frequency.
- **Load driver.** `ops/perf/write_load.py` exercises concurrent maker-checker cycles (`HOLD` and `RELEASE`) over HTTPS against staging, measuring latency and verifying zero balance drift and zero funds left held.
- **Verification.** `DeliveryDrainTest` (4 unit tests, 100% pass) and `test_write_load.py`.

## P-01: release pipeline — 14 November 2026

Production-engineering item P-01 (gate E05) of the [go-live plan](go-live-plan-2026-11-13.md). See [release pipeline](release-pipeline.md).

- **Release candidates.** An `rc` tag runs the full verification, then builds the image from the verified JAR on a digest-pinned base. The job produces CycloneDX and SPDX SBOMs, fails on fixable high or critical Trivy findings, pushes to GHCR, signs keylessly with cosign, attaches provenance and SBOM attestations, and publishes a pre-release with checksummed evidence.
- **Promotion.** A manually started workflow runs in the reviewer-protected `production` environment, so the approval is recorded. It verifies the signature and provenance, then tags the same digest as final, without rebuilding.
- **Still open.** The repository owner must set `JAVA_RUNTIME_IMAGE`, the `production` environment reviewers and tag protection. The first real candidate tag is the end-to-end proof. `ops/tests/test_release_workflow.py` guards the gate order.

## P-08: tenant upgrade orchestration — 14 November 2026

Production-engineering item P-08 (gate E11) of the [go-live plan](go-live-plan-2026-11-13.md). No migration. See [tenant upgrades](tenant-upgrades.md).

- **Rolling bank upgrades.** The migration job upgrades banks one at a time and continues past a failed bank, unless `--stop-on-failure` is set. It supports canary runs with `--only`, writes a per-bank report (`--report`), and exits with code 2 when any bank is left behind.
- **Mixed-version safety.** In production, a bank whose schema is behind the build is refused with `BANK_SCHEMA_UPGRADE_REQUIRED` while other banks keep working. `up.sh` continues after a partial upgrade only with `CBS_ALLOW_PARTIAL_UPGRADE=yes`.
- **Failed-upgrade drill.** An automated drill breaks one bank's migration mid-upgrade. It proves the other bank upgrades, the failed bank keeps its data and previous version, the runtime refuses it, and a re-run completes it. The doc includes the upgrade runbook with the rollback decision point.

## P-07: batch scheduling and operations console — 14 November 2026

Production-engineering item P-07 (F21) of the [go-live plan](go-live-plan-2026-11-13.md). Control migration **V55**. See [batch operations](batch-operations.md).

- **Scheduled cycle preparation.** After each enabled bank's local cut-off, a configured service identity submits the servicing run, then the period close if one is due, then the day close. People approve every step. Rejected requests are not resubmitted, and blockers are recorded rather than retried silently.
- **Operations console.** One page per bank shows cycle status, day-close blockers, pending approvals with their age, every delivery queue with pending and failing counts, exceptions and the scheduler history.
- **Monitoring.** `cbs_batch_blocked_banks` and the `CbsDailyCycleBlocked` alert.
- **Verification.** The integration test for the scheduled cycle, 138 UI tests and the ops tests pass.

## Round 10: Customer notifications go-live — 13 November 2026

Round 10 of the [go-live plan](go-live-plan-2026-11-13.md) makes customer alerts ready for a provider: verified destinations, approved wording, scheduled notices, opt-outs and signed delivery receipts. Control migration **V51** (V50 is reserved for Round 14). See [notifications go-live](notifications-go-live.md).

- **Destination verification.** A new phone number or email receives a one-time code (hashed, 24-hour expiry, five attempts); alerts start only after staff confirm the code the customer reads back. Codes never appear in lists or audit.
- **Approved templates.** Bank wording per event, language and channel through maker-checker (`NOTIFICATION_TEMPLATE`), restricted to the event's placeholders, naming the bank, and two SMS segments at most; built-in English, French and Kiswahili otherwise.
- **Scheduled notices.** Term-deposit maturity (7 days ahead), loan instalment due (3 days ahead) and arrears at 1/30/60/90 days past due, queued once per holder by an idempotent background scan that never changes the bank version.
- **Opt-outs and receipts.** Provider STOP or a branch request suppresses every message to the destination until renewed consent is recorded. A public, HMAC-signed, replay-safe webhook records provider delivery receipts and opt-outs.
- **Monitoring.** Failed-in-24h and oldest-pending metrics with Prometheus alerts.
- **Still open.** The real SMS/email adapter waits for the provider decision (D09); the exit test needs its sandbox. French/Kiswahili wording needs the Round 17 review.
- **Verification.** Notification, statement-alert, joint-account, route-access and production-guard tests pass, as do 124 UI tests and 31 ops tests.

## P-09: money-path invariant and property tests — 14 November 2026

Production-engineering item P-09 (gate E13) of the [go-live plan](go-live-plan-2026-11-13.md). Tests only. See [money invariants](money-invariants.md).

- **Property tests.** Seeded, reproducible generators check six areas: accrual rounding carries conserve value exactly, leap days count, loan schedules repay exactly the principal on monthly dates, daily servicing nets exactly without losing rounding, FX rounding stays within one unit and the spread never pays the customer, and limits never let a total exceed the limit. A deliberate carry mutation is caught.
- **Book invariants.** A generated 30-step sequence of postings and refusals checks after every step that journals and the trial balance balance and that ledgers and account positions agree. It then checks that replaying every delivery changes nothing, and that racing submissions, approvals and deliveries of two overdrawing withdrawals post exactly one.
- **CI.** Both suites run on every push. A nightly workflow explores the properties with a new seed and 20,000 cases each.
## Round 18: Privacy and data protection — 13 November 2026

Round 18 of the [go-live plan](go-live-plan-2026-11-13.md) completes privacy, field-level data protection, legal holds, data-subject request registers, and the statutory retention matrix (Kenya Data Protection Act 2019, POCAMLA s.46, Gate E04). Control migration **V49**. See [privacy and data protection](privacy-and-data-protection.md).

- **Field-level protection for sensitive identifiers.** Added `PersonalDataCipher` implementing deterministic AES-256-GCM tokens (`pd1:`) with tenant-isolated HMAC key derivation and keyed blind indices (`bi1:`). Document numbers are sealed before storage in approval requests, delivery outboxes, registries, and bank schemas.
- **Production guard enforcement.** `ProductionGuard` refuses launch if `cbs.privacy.field-key` is missing, insecure, or using the development key.
- **Statutory retention matrix & archival review.** Codified 7 retention rules covering KYC, transactions, AML monitoring, audit logs, notifications, DSRs, and sessions. Endpoints identify archival review candidates.
- **Legal holds.** Dual-controlled (maker-checker) legal holds (`platform.legal_hold`) freezing customer data against deletion or erasure.
- **Data-subject request handling.** Structured register for data-subject requests (`ACCESS`, `RECTIFICATION`, `ERASURE`, `OBJECTION`, `PORTABILITY`) with SLA due dates and automated subject-access report export. Erasure assessment verifies blockers before action.
- **Customer view audit log.** Every staff view of customer data is audited (`CUSTOMER_PROFILE_VIEWED`, `CUSTOMER_VIEW_LOG_READ`).
- **UI and verification.** Added Privacy & data protection portal view with tabs for subject requests, legal holds, and retention rules; added customer privacy dialog. Tests: 236 backend tests pass (12 privacy tests), 125 UI tests pass.

## Round 17: Localization and accessibility — 13 November 2026

Round 17 of the [go-live plan](go-live-plan-2026-11-13.md) delivers complete localization for the reference Kenyan pilot configuration (English and Kiswahili, plus French) and verifies accessibility compliance (Gate E14, Matrix row F33). See [localization and accessibility](localization-and-accessibility.md).

- **Full Kiswahili and French coverage.** Expanded `ui/src/i18n.js` with 100% parity across all 32 navigation views and operational modules, covering over 710 distinct translated phrases per language.
- **Module journeys.** Localized Privacy & data protection, KYC document management, periodic reviews, account dormancy, deceased customer statuses, branch operations, teller & vault custody, cash counts & shortages, loan arrears, collections, FX books, and payment cases.
- **Statutory error translations.** Localized key regulatory error messages for fail-closed states (`ACCOUNT_DORMANT`, `CUSTOMER_DECEASED`, `BANK_DUTY_REQUIRED`, `HOLD_NOT_ACTIVE`, `INSUFFICIENT_AVAILABLE_BALANCE`).
- **Automated completeness tests.** Added tests in `ui/test/i18n.test.mjs` verifying 100% key parity between English, French, and Kiswahili with zero missing translations, non-empty text, and robust display formatting.
- **Accessibility verification.** Confirmed WCAG 2.1 AA landmarks, skip-link navigation, ARIA live regions for notices, alert roles for form validation, table header scopes, and keyboard modal focus traps. Tests: 129 UI tests pass.

## Round 12: Deposit lifecycle completion — 13 November 2026

Round 12 of the [go-live plan](go-live-plan-2026-11-13.md) completes KYC document object storage, risk-based periodic reviews, account dormancy, deceased-customer workflows, contract/payment-aware account closure, and closure settlement reversal. Control migration **V48**, bank migration **V27**. See [deposit lifecycle completion](deposit-lifecycle-completion.md).

- **Encrypted KYC document storage.** Replaced reference-only documents with AES-256-GCM encrypted object storage (`platform.customer_document`). Keys derived per bank tenant with HMAC-SHA256. Enforces SHA-256 integrity validation on download (`DOCUMENT_INTEGRITY_COMPROMISED`) and statutory retention policies (`DOCUMENT_RETENTION_ACTIVE`).
- **Periodic KYC review.** Reviews scheduled according to AML risk ratings (`HIGH`: 1y, `MEDIUM`: 2y, `LOW`: 3y). Added validation rejecting future review dates (`REVIEW_DATE_FUTURE`) and tracking overdue reviews.
- **Account dormancy & unclaimed assets (D14).** Added `DORMANT` account state. Fail-closed controls prevent all operations on dormant accounts (`ACCOUNT_DORMANT`). Admin endpoints track dormancy candidates and unclaimed assets.
- **Deceased-customer workflow.** Added `DECEASED` customer state with irreversible terminal transition (`CUSTOMER_ALREADY_DECEASED`). Customer operations and eligibility checks fail closed with `CUSTOMER_DECEASED`.
- **Account closure unblocking.** Closure now inspects contract statuses (`status <> 'CLOSED'`) and payment states (`state NOT IN ('SETTLED', 'REFUNDED')`). Accounts with settled contracts and payments are no longer blocked.
- **Closure settlement reversal.** Added `CLOSURE_SETTLEMENT_REVERSAL` operation with maker-checker approvals to reverse closure payouts and unblock accounts when required.
- **UI and verification.** Added KYC document upload/download modals, KYC review dialog with risk-based due dates, customer `DECEASED` badge, account `DORMANT` badge, and settlement reversal UI. Tests: 14 new backend unit tests (100% pass), 123 UI tests pass.

## Round 11: AML and screening completion — 13 November 2026

Round 11 of the [go-live plan](go-live-plan-2026-11-13.md) completes AML, customer and payment screening, case assignment SLAs, and STR draft exports. Control migration **V47**. See [AML screening completion](aml-screening-completion.md).

- **UN consolidated list XML.** Added `UnListXml` adapter to ingest UN Security Council consolidated XML directly into screening entries. Parser enforces XXE protections (disallows DOCTYPE and external entities), merges names/aliases up to 20 with good-quality prioritization, and filters out approximate dates of birth.
- **Scheduled periodic re-screening.** Activated screening lists now declare an optional `rescreen_days` interval (1–366 days). The background engine queues scheduled re-screens when the interval elapses since the last run.
- **M-Pesa beneficiary screening.** Payee names are screened before payout submission, approval, and dispatch. Undismissed matches persist in `platform.beneficiary_hit` across autonomous transactions and fail closed with `PAYMENT_BENEFICIARY_SCREENING_MATCH`. Approved dismissals carry over for the same payee and entry.
- **Case SLAs and investigator assignment.** Compliance cases support assignment to active staff holding the `COMPLIANCE` duty with an SLA due date. Overdue cases surface in the UI and trigger Prometheus alert `CbsComplianceCasesOverdue`.
- **STR draft export.** Suspicious transaction reports export in canonical format (`cbs-str-draft/1`), aggregating bank details, customer subject, grounds, screening/beneficiary hits, monitoring alerts, and recent movements (180-day window, up to 500 records), with SHA-256 digest recorded on the case history.
- **UI and verification.** Added assignee, due date, overdue badges, assignment dialog, STR draft export, and beneficiary match queues to the portal. Tests: 215 backend tests passed (99 DB integration tests skipped without Postgres), 121 UI unit tests passed.

## Duty-scoped direct routes and delivery recheck — 13 November 2026

Round 9b of the [go-live plan](go-live-plan-2026-11-13.md) finishes access governance. See [bank duties](bank-duties.md#direct-routes-round-9b).

- **Direct routes.** About 140 bank-scoped administrator routes are now checked against a route table before the controller runs: lists, details, previews, draft uploads and retries.
  - Reads need audit or a duty of that area.
  - Reference data needs any grant in the bank.
  - Anything else needs the submit duty.
  - A route without a rule is refused while duties are enforced, and a build test fails if one appears.
- **Recheck at delivery.** An approved request posts to the bank database only while the maker still holds the submit duty and the checker the approve duty. Otherwise it waits with `BANK_DUTY_REVOKED_BEFORE_DELIVERY` and posts on retry after the duty is restored. Postings the bank already has a receipt for are always recovered.
- **One classification** of stored requests now serves both the approval-time maker recheck and the delivery recheck.
- **Unchanged default.** Banks that do not enforce duties behave as before. No migration was needed: control stays at V46 and bank at V26.
- **Still open.** Amount bands per duty (D18), review reminders (P-05) and field-level filtering of shared reads.
- **Tests.** Full backend `verify` with database integration tests: 199 passed, 1 opt-in volume test skipped. UI: 119 passed.

## Bank duties and access reviews — 13 November 2026

[Bank duties](bank-duties.md) is Round 9 of the [go-live plan](go-live-plan-2026-11-13.md).

- **Duties.** A bank can now enforce duties for platform administrators. With enforcement on, submitting, approving or even seeing a bank's requests needs an approved, unexpired duty for that bank.
- **Coverage.** There are seven duties: access administration, bank configuration, finance, customer operations, payments, treasury and compliance. They cover every approval action except platform-wide ones and lending-only ones. Lending-only actions keep their lending grants.
- **Grants.** Submit and approve are granted separately, and a read-only audit flag is available. Time-limited (break-glass) duties end on their own. The maker is checked again at approval.
- **Lockout protection.** At least two access administrators must remain while enforced. If that is lost, a dual-controlled recovery request can switch enforcement off.
- **Access review.** A consolidated report shows bank duties together with lending, payment and branch roles, and flags disabled, dormant, time-limited and conflicting access. An auditor signs an attestation bound to a SHA-256 hash of the exact snapshot.
- **Unchanged default.** Banks that do not enforce duties behave exactly as before.
- **Not yet covered (Round 9b).** Direct, non-approval administrator routes.
- **Database.** Control migration V46.

Also in this round:
- `RuntimeDatabaseGuardIntegrationTest` no longer assumes a superuser test login.
- A duplicate UI message key is removed.

Verification:
- Full backend `verify`: **195 passed**, 1 opt-in volume test skipped, zero failures or errors (94 integration scenarios).
- A later fix fixed the key order of the review snapshot, so the hash is the same across restarts. After it, `BankDutiesTest` (now 7 tests) and the duty integration scenario passed again.
- UI: **119 passed**.
- Operations tests were not run locally: there is no Python interpreter on this machine. CI runs them, and this round changed no operations code.

Bank acceptance remains pending.

## Screening and transaction monitoring — 11 November 2026

[Screening and transaction monitoring](screening-and-transaction-monitoring.md) adds approved sanctions/PEP/internal screening lists. Every customer is screened when their approved profile is applied, and again when a new list version is activated (in resumable chunks). Name matching tolerates accents, word order and small misspellings, and uses birth dates to rule out namesakes. An open possible match restricts the customer, and any organization they act for, until it is escalated to a case or its dismissal is approved.

Post-event transaction monitoring adds four approved rules per currency: large cash, structuring, velocity and unusual amount. They run on teller cash, funding, transfers, withdrawals and M-Pesa payouts. Alerts never block the customer, so the customer is not tipped off; escalation opens a restricting case.

Lists, entries and monitoring history are immutable. Control migration V45.

Verification: full backend `verify` **188 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **112 passed**; operations **29 passed**; restore drill PASS with V45 through the packaged launcher. Bank acceptance remains pending.

## Production-readiness round — 11 November 2026

[Production readiness](production-readiness.md#production-readiness-round--11-november-2026) adds an automated access review of every route (262 method/path pairs), per-client rate limiting with a stricter budget for failed authentication, stronger browser headers, and dependency scanning (npm audit, OSV-Scanner and Dependabot). Hot-path indexes cut daily-limit and per-account lookups from 24–82 ms to under 1 ms on 1M synthetic operations (control V44, bank V26). Also new: a staging load probe, a one-command staging bring-up, CI image and Compose checks, and UTC-pinned tests. The restore drill was re-run and passed. An end-to-end check through Caddy showed that forged forwarding headers do not get around the limits.

Verification: full backend `verify` **182 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **108 passed**; operations **29 passed**; restore drill PASS (SQL and packaged launcher).

## Management and financial reporting — 11 November 2026

[Management and financial reporting](management-and-financial-reporting.md): balance sheet (with unclosed current result) and income statement for any date/period with comparatives, built from posted journal lines by chart hierarchy, as JSON, CSV or PDF; a management dashboard (deposits by product/branch, top-10 concentration, loan ageing and non-performing ratio, cash holdings, work waiting); and approved report definitions mapping chart accounts to template lines as groundwork for CBK returns (official templates still needed). Control V43.

Verification: full backend `verify` **172 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **108 passed**; operations **21 passed**. Bank acceptance remains pending.

## Period close and signer capture — 11 November 2026

[Period close and signer capture](period-close-and-signer-capture.md): teller cash withdrawals and M-Pesa payout requests now collect authorising signers for mandated, organization and minors' accounts (branch-scoped mandate lookup added). Banks with an approved close policy must close each month on its last business day: an approved, balanced trial balance per entity/currency; at fiscal year end, closing journals sweep income and expense into retained earnings with balance re-checks, an immutable receipt and safe retry. Approval seals the day in both the control plane and the bank database. No reopening of closed periods (corrections post in the open period). Control V42, bank V25.

Verification: full backend `verify` **169 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **107 passed**; operations **21 passed**. Bank acceptance remains pending.

## Organizations, joint and minors' accounts — 11 November 2026

[Organizations, joint and minors' accounts](organizations-joint-and-minor-accounts.md): customers can be organizations (registration certificate; type immutable) with approved directors, beneficial owners (ownership %) and representatives; an organization transacts only with a recorded director and no restricted related person. Approved account mandates add joint holders, authorised signatories or guardians with any-one / any-two / all signing rules. Withdrawals, transfers and closure settlements on mandated, organization and minors' accounts must name enough distinct, eligible authorised signers; minors' and organization accounts cannot be debited without a mandate. Statements list all holders; alerts go to every opted-in holder. Teller UI signer capture and M-Pesa payouts from mandated accounts remain open. Control V41.

Verification: full backend `verify` **168 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **105 passed**; operations **21 passed**. Bank acceptance remains pending.

## Statements and customer alerts — 11 November 2026

[Statements and alerts](statements-and-alerts.md): CSV statement exports now stream with no 10,000-entry cap; printable A4 PDF statements in English, French and Swahili with exact (never truncated) amounts, totals and page numbering. Customer credit/debit alerts use an approved, independently reviewed contact preference (SMS/email, language, events, consent reference) and a transactional outbox written with each posting; alerts carry no balances or full account numbers. Delivery retries up to 5 times with an audited manual resend. No real SMS/email provider is connected yet (alerts stay queued); a test sender exists for development and is refused in production. Control V40.

Verification: full backend `verify` **167 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **103 passed**; operations **21 passed**. Bank acceptance remains pending.

## Daily processing at bank scale — 11 November 2026

[Chunked daily processing](servicing-chunked-batches.md): the 1,000-account cap is removed. Approved runs post in resumable chunks (default 500 accounts, one bank transaction and immutable receipt per chunk); a failed chunk stops the run with its code and a retry resumes there, with the day sealed until all chunks post. Frozen accounts, ineligible or compliance-restricted customers and accounts unable to afford the day's fee are excluded individually with recorded, approved reasons instead of blocking the run; excluded accounts receive nothing for that period (bank decision pending on accrual for frozen accounts and catch-up). Interval limit raised from 31 to 366 days. Preparation reads balances in bulk. Requires bank schema V24 and control V39.

Verification: full backend `verify` **162 passed**, 1 opt-in volume test skipped, zero failures/errors; UI **101 passed**; operations **21 passed**. Opt-in volume run: **1,050 accounts** opened and funded through the normal workflow, serviced in 525 two-account chunks and reconciled exactly per account and in total (preview 0.4 s, approval 1.3 s, posting 30.0 s on a local fixture). Bank acceptance remains pending.

## W03 resume and final closure settlement — 11 November 2026

[Closure settlement report](w03-closure-settlement.md): the interrupted W03 retry was completed on a fresh isolated database (full backend 160 passed before changes). Positive-balance closure now works in two independently approved steps: a `CLOSURE_SETTLEMENT` that pays out the whole balance (including the product minimum) to an approved asset ledger and records any sub-unit interest carry as forfeited, followed by the existing closure. Interest is settled through the last completed daily run; a servicing policy alone no longer blocks closure. Settled accounts accept nothing but closure and are skipped by daily servicing. Contract/payment-history accounts, pro-rata closing-day interest and settlement reversal remain out of scope. Requires bank schema V23 (Prepare account storage) and control V38; a `CLOSURE_SETTLEMENT` transaction limit must be approved.

Verification: full backend `verify` **161 passed**, zero failures/errors/skips; UI **100 passed**; operations **21 passed**. Bank acceptance remains pending.

## W02 / W03 functional work

[Validation and closure report](w02-w03-functional-validation.md): fixed first journal delivery after approval-participant deactivation, retained lost-acknowledgement recovery, and added Closure Check plus independently approved zero-balance closure. Final payout, interest/fee/tax settlement and linked contract/payment closure remain partial. Bank acceptance is still pending. Existing banks need bank schema V22.

## Deployment and recovery increment

[Deployment and recovery tooling](production-deployment-recovery.md) now includes HTTPS staging templates, separate migration/runtime credentials, encrypted coordinated backups, isolated restore validation, monitoring rules and operating procedures. The expanded local restore drill verifies a fictional funded customer, balances, journals, approvals, pending postings and restricted runtime database privileges. Real hosted deployment, MFA/role acceptance, off-host durability, failover and bank-approved recovery objectives remain pending.

## Overall position

The application has an implemented administration and accounting foundation and an
initial deposit-product catalogue, individual customer onboarding and deposit account opening/funding with customer balances. It is a development system, not a complete
production CBS. Approved received-deposit credits are available. Holds/releases, recorded withdrawals, same-currency internal transfers, full funding reversals and customer statement views have now been added. Counting modules would give a misleading completion percentage:
the remaining financial and operational modules differ greatly in size. The latest increment adds [bank-scoped lending roles and approval limits](bank-scoped-lending-access.md), scoped approval queues, permission reports and revocation enforcement. The preceding increment validates the lending lifecycle and adds [loan arrears and collections](loan-arrears-collections.md), posted interest/fee ageing, cash-backed promises, assignments and escalation tracking; see [lending controls](lending-controls.md), [contract corrections and term servicing](contract-corrections-servicing.md), and [FX valuation](fx-valuation.md).

The stack is Java 21, Spring Boot, PostgreSQL on Neon for development, and a browser
administration portal with local Keycloak identity integration. Shared bank
configuration lives in the control database; ledger/journal records are isolated
in each bank's dedicated database. Product definitions use the control database.

## Module status

| Module | Implemented capability | Remaining scope / readiness |
| --- | --- | --- |
| 00 Foundation | Build, packaging, health, migration infrastructure, CI workflow | GitHub CI execution, hosted deployment and recovery not verified |
| 01 Bank administration | Bank registration, legal entities, database binding/provisioning, lifecycle and audit | Live operational acceptance and backup/restore drills |
| 02 Staff and access | Staff identities, bank/branch grants, scoped lending roles and approval limits, immutable access review history, MFA checks and sessions | Restore working MFA for normal use; production identity setup and access review |
| 03 Approval Inbox | Independent approval, rejection, cancellation, history, stale-request and retry protection | Extend scoped approval policy coverage to remaining administration modules |
| 04 Reference data | Currency, language, rounding rules, business calendar and settings approval | Country-specific policy setup and acceptance |
| 05.1 Chart of Accounts | Hierarchy, categories, posting definitions and approvals | Bank-specific accounting/regulatory chart setup |
| 05.2 Ledger Accounts | Per-bank ledger storage, approval delivery and retry recovery; customer subledgers and holds integrated | Bank accounting acceptance and lifecycle extensions |
| 05.3 Journals | Balanced single-currency manual journals, full reversal, balances and immutable records | Customer transaction orchestration, FX transactions |
| 05.4 Reports | Trial balance, ledger statements, turnover reconciliation and CSV | Consolidated FX, regulatory and customer reports |
| 05.5 Business Day Close | Readiness checks, independent approval, date locks, next-working-day advancement, totals and recovery | Full end-of-day batches, period/year closing and controlled reopening |
| 06.1 Product Factory | Savings/current definitions, currency, minimum rules, liability mapping, activation approval and revisions | Interest, fees, eligibility, overdrafts and effective-date policies |
| 07.2 Compliance cases | Manual evidence, independent review, immediate restrictions, review expiry and current-profile checks | Mandatory verification, automated screening, scoped investigators and country acceptance |
| 07 Customer onboarding | Individual profiles, document/manual-review references, duplicate checks, status approval and recoverable bank storage | Automated verification/screening, corporate customers, guardians, periodic review and privacy controls |
| 08.1–08.2 Accounts and deposits | Approved opening/funding, unique numbering, pinned terms, exact customer balances, dedicated liability ledger, atomic journal integration and retry recovery | Holds/releases, recorded withdrawals and full funding reversals implemented; limits, funded-account closure and broader corrections remain |
| 09 Internal transfers | Same-bank/entity/currency transfers, scoped branch permissions, mandatory limits, full reversals, atomic posting and retry safety | Broader approval policy and bank acceptance |
| 10 Teller and cash | Vault/till custody, customer cash, limits, approvals, exceptions/adjustments, reversals, interbranch transit, denomination inventory and printable receipts | Carrier custody, stock exchange/reclassification, emergency reassignment, extended receipts and bank acceptance |
| 11 Interest, fees, accruals | Approved effective-dated policies, daily capitalization, fixed business-day fees, explicit withholding/fee taxes and fractional carry | Tiered/value-date interest, deferred capitalization, exemptions, servicing corrections and country acceptance |
| 12 Business-day processing | Preview/approval, atomic daily batch, idempotent recovery, statement integration and day sealing before close | Scalable chunked batches, schedules, period/year close and production load acceptance |
| 13 Statements/notifications | Customer statement view with running balances and bounded backend CSV export | Complete portal export, PDF, delivery preferences, templates and delivery tracking |
| 14 External payments | Safaricom M-Pesa Kenya B2C sandbox adapter, approved payouts, single dispatch, status query and callback observations | Actual sandbox acceptance, production certification, collections/other rails, beneficiary screening and richer exception handling |
| 15 Settlement | Normalized full CSV imports, payout/GL matching, wallet balances, fee adjustments, duplicate controls, corrections, independent closure and exports | Provider-native format acceptance, timing differences, split/net settlement, larger automated imports and production controls |
| 16 FX | Approved rates/conversions, atomic currency legs, full reversal, approved closing valuation, weighted-average trading-book realized/unrealized P&L, long/short limits and day-close gates | Full-bank monetary-item translation, cross rates, feeds, policy/limit amendments, valuation corrections and accounting acceptance |
| 17 Term deposits | Fixed-rate opening/accrual, withholding, maturity payout, current-day corrections, renewal, full early closure and in-app maturity alerts | Product catalogue, partial closure, broader corrections, customer messaging and bank acceptance |
| 18 Lending | Loan applications, manually evidenced affordability/security, independent credit policy/review/conditions, disbursement gate, fixed-rate schedules/accrual/repayment, current-day corrections, principal/interest/fee ageing, approved fee assessment/payment/waiver, assigned collection cases, cash-backed promises, escalation tracking, approved restructuring, assessed allowance accounting and full write-off | Scoped credit roles, aggregate/bureau exposure, scalable arrears snapshots, outbound collections, fee tax/corrections, automated IFRS 9, richer restructuring, recoveries, regulatory reporting and bank acceptance |
| 19 Reporting expansion | Accounting reports only | Operational dashboards, customer and jurisdiction-specific regulatory reporting |
| 20 Operational readiness | Some engineering controls and tests | Data migration, security/volume tests, backups, disaster recovery, monitoring, runbooks and pilot acceptance |

Implemented does not mean production-approved. English/French/Swahili interface
labels exist, but a full translation review and translated product/customer content
remain outstanding. Own-customer currency conversion and functional-currency trading-book valuation/P&L are implemented. Full-bank foreign-currency translation and regulatory acceptance remain outstanding.

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

## 7 October 2026 — inventory, receipts and daily servicing

Added approved denomination catalogues, one-time physical opening inventory, movement-level quantity checks, reversal and shipment stock controls, printable numbered cash receipts and audited reprints. Added Deposit servicing with effective-dated approved policies, daily interest capitalization, explicit fees/taxes, fractional accrual carry, per-account previews, atomic bank posting, customer/GL and statement integration, retry recovery and a business-day seal. Control V22/V23 and bank V13/V14 are required.

Validation: full backend suite 106 passed; UI suite 50 passed; subsequent focused 5-test run passed after final policy-correction and inventory safeguards. Fictional browser previews verified receipt and daily calculation layouts. No live rates, tax policies, catalogues or financial transactions were seeded.

See [cash guide](cash-inventory-and-receipts.md) and [servicing guide](deposit-servicing.md) for setup and exact limits. This implements the first daily-capitalization path; tiered/backdated interest, deferred capitalization, tax exemptions, servicing correction, scheduled/scalable batches and production acceptance remain outstanding.

## 7 October 2026 — compliance cases and enterprise review

Added the manual compliance-case workflow, independent clearance in Approval Inbox, immutable evidence, customer restrictions and review expiry/profile-change checks. Queued account delivery and daily servicing now recheck eligibility before first posting while preserving receipt-based recovery. Cash denomination capacity is validated before approval.

Verification: 108 backend tests, 53 UI tests and a final 2-scenario recovery run passed. Browser verification used fictional data. No live case or financial decision was created. See [compliance guide](compliance-cases.md) and [enterprise review](enterprise-readiness-review-2026-10-07.md). The review covers all implemented areas and concludes that production banking acceptance remains outstanding. Earlier dated sections are historical snapshots; the module matrix and latest review describe the current scope.

## 7 October 2026 — M-Pesa sandbox payments and reconciliation

Implemented the first provider increment for Safaricom M-Pesa Kenya: approved KES payouts, atomic customer/GL debit before dispatch, pinned sandbox provider binding, one network submission, uncertain-outcome protection, asynchronous status queries, non-authoritative callback observations, immutable per-payment statement evidence, independent matching/refund and daily-processing blockers. Mandatory current compliance clearance applies to this payment flow.

This is a sandbox implementation, not an activated or certified provider integration. Credentials/HTTPS callback ingress and actual Daraja contract testing remain outstanding. Complete statement import, net/fee reconciliation, production access and beneficiary-screening controls remain pending. See [operator and setup guide](payments-mpesa-sandbox.md) and verification.md for exact scope and evidence.

## 7 October 2026 — full normalized M-Pesa statements

Added the M-Pesa statements page, strict CSV validation and column mapping, immutable import evidence, exact wallet balance and movement coverage, independent payout/journal matching, fee/unknown-movement accounting, correction/reversal, duplicate acknowledgement, conflict review, report exports and statement-period sealing. Open imports block daily processing. Control V26 / bank V14.

This completes the bounded normalized-file workflow, not provider-native format certification or production acceptance. Actual export validation, timing/split/net settlements, scalable acquisition and bank accounting acceptance remain. See [statement operator guide](mpesa-statement-reconciliation.md).

## Payment lifecycle increment — 7 October 2026

Added **Payment exceptions**: bank-scoped reconciler/supervisor/auditor permissions, investigation ownership and evidence history, overdue indicators, approved dated settlement carry-forwards, and full provider-return credits tied to the original customer debit. Statement and payment review now use the scoped payment workspace. Returns require matching provider credit evidence and independent approval; replaying delivery cannot credit the customer twice. Day close checks investigation deadlines and return posting.

See [operator workflow and acceptance limits](payment-lifecycle-exceptions.md). Local regression: 120 backend tests and 65 UI tests. Actual M-Pesa export validation and live provider sandbox acceptance remain unverified. Remote reversal initiation, partial returns, suspense handling and outbound overdue notifications remain pending. Next planned modules: FX & Exchange Rate Management, Term Deposits, Lending. No production-completion percentage is asserted.


## 8 October 2026 — FX, term deposits and lending

Implemented first operational versions in the requested order: independently approved rate books and atomic own-customer currency conversions; fixed-rate term deposit opening, accrual, withholding and full maturity payout; fixed-rate loan disbursement, equal-principal schedules, accrual and interest-first repayment. Customer statements, bank-wide limits, immutable receipts, retry recovery and daily-close checks are integrated. See [supported flows and explicit remaining gaps](fx-term-deposits-lending.md). This does not complete the production CBS or the full lifecycle acceptance criteria in the competitive gap report. Historical progress entries above describe their original implementation dates.

## 8 October 2026 — contract corrections and term-deposit servicing

Implemented current-open-day opening/accrual/repayment reversals with independent approval and atomic bank posting, plus explicit early closure, renewal with payout or capitalization, immutable effective terms and in-app maturity alerts. See [operator guide and remaining scope](contract-corrections-servicing.md). Earlier roadmap entries are historical. Next functional priorities are lending assessment, collateral, arrears, restructuring and impairment; production acceptance remains outstanding.

## 8 October 2026 — lending controls and credit assessment

Implemented the Loan applications & credit review page, immutable financial/evidence dossiers, policy-based affordability and reviewer authority, collateral/guarantor evidence, independently verified conditions and mandatory loan disbursement gates. Added principal days past due, collections follow-up notes, approved replacement schedules, assessed impairment journals and fully provided write-offs. See [operator guide and explicit boundaries](lending-controls.md). No bank credit policy, live customer credit decision or financial transaction was seeded.

## Lending validation and collections — 8 October 2026

Completed isolated lending workflow validation before implementing the arrears/collections page. The release includes actual posted arrears by due date, six ageing buckets, immutable assignment/contact/promise/escalation history and independently approved fee accounting. See [setup, accounting rules and remaining acceptance](loan-arrears-collections.md). No real bank financial test records or new MFA exception were created.
