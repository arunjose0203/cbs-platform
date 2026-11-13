# CBS functional and production acceptance test plan

Prepared 13 October 2026; re-baselined 14 November 2026 (Task 0.8). Baseline: `08df29af9cac1e3e2979bf9df06eb0ea36429b29` (Control V55, Bank V29).

This is an execution plan for the existing CBS, not a certificate of completeness or a new test run. The accompanying catalogue contains **122 records: 107 planned tests and 15 evaluated gaps**, covering **34 module areas**. Following Rounds 9–19 and P-01–P-10, 14 of the 15 gaps are now **IMPLEMENTED** with automated test coverage, leaving 1 gap (GAP-08) **OPEN** awaiting bank live provider contracts (decision D09). Owners listed are proposed roles; named testers and signatories must be assigned.

## 1. Start here

1. Appoint a bank acceptance lead and an independent finance reviewer. Record the release SHA, artifact digest, schema versions, bank scope and environment in the evidence repository.
2. Prepare an isolated staging installation and fictional banks. Do not execute destructive, fault-injection or recovery tests against live Neon data.
3. Configure real MFA and distinct maker, checker, teller, credit, collections and auditor identities. A shared administrator or temporary MFA bypass does not satisfy access acceptance.
4. Rerun the complete automated backend, UI and operations suites on this release. Investigate skips and failures; retain the logs as release artifacts.
5. Filter the checklist to **W02**, then execute administration, bank/branch access, approval, accounting and transaction-limit cases. Start with FOCUS-01 to FOCUS-03, which cover disabled approval participants and safe recovery.
6. Fix and independently retest any P0 failure before accepting money workflows. Run the affected regression tests after each fix.
7. Execute **W03** deposits, including FOCUS-04 to FOCUS-13 and E2E-02. Accept only the supported zero-balance closure scope; positive-balance settlement remains a gap.
8. Continue through the phases below. Record actual results and evidence for each variation, not just a tick beside a module.

## 2. Scope and sequence

| Phase | Scope | Main acceptance owner | Dependency / exit |
| --- | --- | --- | --- |
| W01 | First-bank scope, roles, products, currencies, languages, volumes and evidence pack | Bank product owner + test lead | Named owners and IMPLEMENT / BOUNDED / EXCLUDE decisions |
| W02 | F01–F10 administration, identity, roles, approvals, reference data, chart, ledgers, journals, reports and day close; F17 limits | Operations + finance + security | Access and accounting tests pass before financial UAT |
| W03 | F11–F16 products, customers, compliance, accounts, funding and account operations; F22 statements | Deposit operations + finance | Customer and ledger balances reconcile; required closure/customer gaps resolved |
| W04 | F18–F21 teller, denominations, receipts, interest, fees, taxes and daily processing | Cash operations + finance | Physical stock, monetary balances and servicing worksheet agree |
| W05 | F23–F25 M-Pesa payout, statement import, reconciliation, returns and exceptions | Payments operations + finance | Real provider format and sandbox acceptance evidence; no assumed production approval |
| W06 | F26–F32 FX, valuation, term deposits, corrections, lending and collections | Treasury + credit + finance | Full offered-product lifecycles and corrections accepted |
| W07 | F09/F22/F33 reports, exports, language, currency and tenant behavior | Operations + local-language reviewers | Required country/language journeys and reports accepted |
| W08 | F34 deployment/recovery plus security, performance and end-to-end acceptance | Infrastructure + security + bank acceptance lead | Hosted restore, monitoring, security and bank-defined capacity/recovery targets pass |

The sequence is dependency based, not a one-day completion promise. Estimate duration after assigning people and confirming environments, integrations and scope. Repeat country-dependent tests for Kenya, Uganda and Rwanda when those offerings are in the release.

Each module has a supported-lifecycle case and a negative/boundary case. Fourteen focused regressions cover the latest W02/W03 changes; nine end-to-end journeys and sixteen non-functional cases add cross-module coverage. Fifteen gap records require scope or implementation decisions. Multi-module case references overlap and must not be added as unique tests.

## 3. Entry criteria and environment

- Use a release candidate built from the recorded SHA, with the UI and executable artifact from that same build. Record control and every bank migration version; this baseline includes control V55 and bank V29.
- Use separate control and bank databases, approved bindings, restricted runtime credentials and separate migration credentials. Supply secrets through the approved secret mechanism; never put passwords, tokens or QR secrets in test evidence.
- Create at least two banks and two branches. Prepare active, suspended and unauthorized fixtures. Record issuer, role, membership, currency authority and limits for each identity.
- Confirm browser access, HTTPS, identity-provider connectivity, MFA, time synchronization and session expiry. Test development exceptions separately from production authentication.
- Seed only fictional customers. Reconcile opening balances and record product, policy, calendar and exchange-rate revisions before each case.
- Use a controlled fault-injection environment for concurrency, database failures, backup restore and acknowledgement-loss tests. Keep outbound provider traffic disabled in restored environments.
- Obtain a redacted real M-Pesa statement and sandbox access before provider acceptance. Mocked callbacks prove internal handling only. Missing prerequisites mean BLOCKED when execution is attempted.
- Bank owners must specify offered currencies/languages, financial conventions, limits, regulatory reports, peak load, latency targets, backup retention, RPO and RTO. No production thresholds are invented by this plan.

## 4. Test fixtures and independent financial expectations

These amounts and rates are **fictional test inputs**, not country tax rules or production bank policies. Prepare separate fixtures or restore the starting snapshot between incompatible cases. Use decimal arithmetic and the bank's approved scale and rounding policy.

| Fixture | Preparation and expected results |
| --- | --- |
| B0 — isolation and authority | Banks KE_A and KE_B, each in a separate database; branches 01 and 02. Assign distinct maker/checker plus bank/branch-limited teller, credit, collections, finance and auditor users. Include revoked and disabled identities. Repeat country-specific scenarios with UG_A and RW_A if offered. Confirm currency precision and approved timezones from configuration. |
| D1 — deposits | KES product with minimum balance zero. Account A starts at 1,000.00; account B starts at zero after an independent 100.00 funding and full reversal. Hold 200.00 on A: posted balance remains 1,000.00, available becomes 800.00. Release it, then transfer 250.00 A→B: A=750.00, B=250.00. Withdraw 100.00 from B: B=150.00. Reverse that withdrawal once: B=250.00. Combined final balance=1,000.00. Verify cash/control counterpart ledgers at every step. |
| C1 — bounded closure | Minimum balance zero, no contract or payment history, no applicable servicing policy/carry/run and no pending work. Fund 100.25 KES then fully reverse to zero. Independently approve closure. Closure changes state and records a receipt but creates no financial journal; retained history remains visible and further funding/reopening is rejected. Run blocker variations on separate fixtures. |
| T1 — cash | Approved KES 100 denomination. Vault starts with 20 notes=2,000.00; till starts empty. Transfer 10 notes to till: vault=1,000.00, till=1,000.00. Customer deposits 5 notes then withdraws 2 notes: till=13 notes=1,300.00, vault=1,000.00; bank cash total=2,300.00. Customer net cash deposit=300.00. Receipts, inventory, custody journals and customer balances must agree. |
| S1 — daily servicing | KES balance 100,000.00; 3.65% annual simple interest, Actual/365, initial carry zero, HALF_EVEN rounding. Test interest tax 10%, flat business-day fee 2.00 and fee tax 10%. One day: gross interest=10.00, interest tax=1.00, fee=2.00, fee tax=0.20, net=6.80, closing balance=100,006.80. Separate three-calendar-day interval with one business-day fee: gross=30.00, interest tax=3.00, fee=2.00, fee tax=0.20, net=24.80. Finance must verify the configured interval/fee convention before execution. |
| P1 — payments | Provider sandbox with approved limits, fictional recipient permitted by provider, known starting balances and actual redacted export. Prepare successful, failed, timed-out, duplicated and returned transaction references. Agree gross/net/fee mappings before importing. Do not substitute generated statement data for provider-format acceptance. |
| L1 — lending | Finance-approved fictional principal, rate, schedule, day-count, repayment allocation, fee, impairment and write-off worksheet. Approved credit policy and currency/amount authority; independent assessor and approver. Include satisfied and unmet conditions, overdue installments, collateral and document-expiry variations. Expected schedules must be calculated independently of CBS before testing. |
| R1 — recovery | Coordinated snapshot of control database, all bank databases, identity configuration and required deployment configuration. Include completed, pending and bank-committed/unacknowledged work. Record balances, trial balance, approvals and receipt IDs before backup. Restore only to isolated targets; compare the same records and ensure retries do not duplicate money. |

For FX, finance supplies an independent two-currency worksheet: original rate, approved closing rate, monetary position, functional carrying amount, realized/unrealized difference and rounding residual. Do not infer whole-bank FX valuation from the supported conversion-book valuation alone.

## 5. Controls applied to every relevant case

| Gate | Required evidence |
| --- | --- |
| G01 Isolation/current authority | Other-bank and branch reads, writes, exports and approvals denied; revoked authority checked before new posting |
| G02 Independent approval | Maker cannot self-approve; reviewer operation/currency/amount authority and current eligibility rechecked |
| G03 Accounting conservation | Balanced journals by currency/entity; balances equal movements; customer, contract and control ledgers reconcile exactly |
| G04 Atomicity | Injected bank failure leaves no partial customer/contract/journal/receipt effect |
| G05 Retry/concurrency | Same key and payload posts once; changed payload conflicts; lost control acknowledgement recovers from immutable receipt |
| G06 Corrections/dates | Original immutable, linked reversal once; ineligible dependent or closed-day correction rejected |
| G07 Evidence/reporting | Actor, reason, bank, business date and original/approval/journal/receipt IDs preserved; exports match accounting |
| G08 Unsupported scope | Unsupported action or size limit produces an explicit error; no silent truncation or simulated external success |

Financial evidence must include before/after customer balances, holds, contract balances, journal lines, ledger balances and any pending-delivery receipt. A successful HTTP response or screenshot alone does not prove accounting correctness. Preserve original records after reversals.

## 6. First acceptance session

1. Record release/environment and check migration versions and reconciled starting balances.
2. Sign in with real MFA; reject an unregistered/disabled identity and test session revocation.
3. Prove KE_A users cannot read, export, approve or post KE_B records; repeat branch restrictions through APIs as well as the UI.
4. Submit a configuration or journal change; demonstrate self-approval and out-of-limit reviewer rejection, then valid independent approval.
5. Post a small balanced journal, retry it, reverse it once and compare original/reversal/audit records.
6. Execute FOCUS-01/02 with a disabled maker/checker before first posting: no money effect. FOCUS-03: a previously committed bank receipt must still acknowledge safely after disablement.
7. Run D1 and verify every balance against the worksheet, not against another CBS screen alone.
8. Run C1 closure plus nonzero-balance, hold, pending-work, historical-contract/payment and servicing blockers. Do not force removal of history to make closure pass.
9. Check statements, trial balance and reconciliation; exercise day-close blockers before allowing a correctly approved close.
10. Log defects, assign owners and review evidence with finance. Retest fixes on the recorded replacement release.

## 7. Execution record, statuses and defects

Use the workbook Cases sheet. Filter by phase, priority or module. Update the amber execution fields: status, actual result, evidence reference, defect ID, tester, date and release. Keep evidence in a controlled repository with one folder/record per case and variation. A row containing several variations is PASS only when all required variations pass. Add variation IDs such as `FOCUS-05/hold` to evidence.

| Status | Meaning |
| --- | --- |
| NOT RUN | No execution evidence recorded for this release |
| PASS | Required steps and controls succeeded; actual result, evidence, tester, date and release recorded and reviewable |
| FAIL | Actual behavior differs from the agreed expected result; link a defect |
| BLOCKED | A prerequisite/environment/integration prevents execution; record blocker and owner |
| NOT IMPLEMENTED | Required capability is missing; link a gap/implementation item |
| EXCLUDED | Named bank owner approved exclusion from this release; retain rationale and decision evidence |

Never convert a known gap to PASS merely because the unsupported operation is rejected. If implemented later, add its acceptance scenarios and new release baseline. Workbook counts describe test records only, not production-readiness percentages. The initial 122-row range is fixed; extend tables and summary formula ranges together when adding cases.

P0 means execute first because of financial integrity, isolation or critical workflow risk. P1 follows for remaining required functions. Test priority is not defect severity. Classify defects as Critical (cross-bank exposure, duplicated/lost money, unrecoverable records), High (required function/control fails without an acceptable workaround), Medium or Low, according to bank impact.

Every defect records: ID; case/variation; release and environment; role/bank/branch; initial state; exact reproduction; expected/actual amounts and states; request/approval/journal/receipt IDs; sanitized evidence; severity; owner; fix SHA; regression link; independent retest and closure decision. Do not include access tokens, credentials or real customer identifiers.

## 8. Automated regression execution

These commands are instructions, not evidence that tests were run while creating this plan. Use Java 21, the repository Maven wrapper script, the UI toolchain and Python. From the repository root:

```powershell
npm.cmd ci --prefix ui --ignore-scripts
npm.cmd test --prefix ui
./scripts/mvn.ps1 --batch-mode --no-transfer-progress verify
python -m unittest discover -s ops/tests -p "test_*.py"
```

The default Maven run skips database integration tests unless explicitly enabled. Follow the integration setup in README before the release run: supply `CBS_DB_URL` and the test database credentials through the documented environment configuration, pointing only to disposable local test infrastructure; then set `$env:CBS_INTEGRATION_TESTS = 'true'` and rerun Maven verify. The integration fixture requires database creation/drop privileges on that isolated server. Do not grant those privileges to production runtime users or point this fixture at live Neon banks. Remove temporary test environment variables after the run.

Retain test counts, failures, errors, skips, logs and artifact digest. Run the packaged recovery drill separately using the deployment/recovery runbook; passing Python unit tests does not establish a successful restore. A full green release run is required because the previous full run had errors followed by a narrower successful rerun.

## 9. Previous evidence and remaining acceptance

Source: the W02/W03 execution report linked below. These runs overlap; do not sum their counts.

| Earlier evidence | Recorded result |
| --- | --- |
| W02 targeted backend | 69 passed |
| Initial W03 | 10 passed |
| UI | 98 passed |
| Operations unit tests | 21 passed |
| Full backend | 160 executed: 155 passed, 5 database-free startup errors |
| Final affected tests/package after wiring fix | 19 passed, including all five startup cases |
| Packaged V22 isolated recovery drill | PASS; small-fixture restore 11.583 seconds, not a production RTO |
| Independent bank UAT | NOT RUN; sign-off PENDING |

Known boundaries include positive-balance/final-charge closure settlement; corporate/joint/beneficial-owner mandates; automated screening; large-batch processing; PDF/email/SMS delivery; live provider certification; partial returns/netting/suspense; broader FX valuation; extended contract corrections and lending recovery; country reports/channels; remaining delegated administrative roles; and deployed failover/PITR evidence. See GAP-01–GAP-15 and the governing matrix for exact scope. Configuration of KES, UGX or RWF does not establish country compliance.

## 10. Release exit and sign-off

- Every required P0/P1 scenario and applicable G gate passes on the release candidate, with independently reviewed evidence. No unresolved Critical/High defect affects offered workflows.
- Customer, contract, cash, settlement and ledger totals reconcile exactly under the agreed rounding rules. No unexplained journal, pending posting, suspense difference or duplicated receipt remains.
- Known gaps are implemented and accepted, or explicitly excluded by the bank with operational consequences recorded. Excluding a mandatory country/security requirement is not an acceptable route to launch.
- Real identity, least privilege, MFA, security review, dependency review and secret-management checks pass.
- Approved load/latency targets are demonstrated with realistic volumes and concurrency. The current bounded processing/export limits are checked against bank needs.
- Coordinated backup, isolated restore, recovery of pending work, actual alert delivery and the required deployed failover/PITR capability meet signed RPO/RTO targets.
- Bank operations, finance, credit/treasury/payments owners where applicable, security, infrastructure and the release owner sign the exact offered scope, release and environment. Dates/names remain unassigned until that review.

Functional test completion alone is not production approval. Retest affected cases after changes to code, policies, permissions, migrations or provider configuration.

## References

- [Functional Gap & Acceptance Matrix](../functional-gap-acceptance-matrix-2026-10-09.md): governing F/A/G scope and W01–W08 work packages.
- [W02/W03 validation report](../w02-w03-functional-validation.md): latest bounded closure and participant-revocation implementation/evidence.
- [Machine-readable test catalogue](cbs-test-cases-2026-10-10.json): draft catalogue dated 10 October, finalized in this plan on 13 October against the same SHA.
- [Deployment and recovery runbook](../production-deployment-recovery.md).
- [Repository setup and integration test instructions](../../README.md).
