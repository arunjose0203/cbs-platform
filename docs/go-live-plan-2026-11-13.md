# Go-live plan for the remaining work

Prepared 13 November 2026. Baseline: branch `cbs-rounds-1-8`, control schema **V45**, bank schema **V26**; backend `verify` 188 passed, UI 112 passed, operations 29 passed.

This plan sequences everything still needed to take the CBS from its current state to a **bounded pilot go-live with one Kenyan bank**. It builds on the [functional gap matrix](functional-gap-acceptance-matrix-2026-10-09.md), the [production release gates](production-readiness.md), the [competitive gap report](competitive-gap-report-2026-10-07.md) and the [acceptance test plan](testing/cbs-test-plan-2026-10-13.md). It does not replace them.

## 1. Where we are

| Track | Estimate | What is left |
| --- | --- | --- |
| Feature code for a first Kenyan bank | ~75–80% | The remaining gaps listed in section 5, most of them conditional on bank decisions |
| Production engineering | ~45% | Everything so far is verified only locally. Still needed: hosted CI, staging, PITR/off-host backup, alerting, load/soak testing and a pen test |
| Bank acceptance and certification | ~0–5% | 0 of 34 acceptance cases have been run, and the providers and regulator are not yet engaged |
| **Overall, to go-live** | **≈45–50%** | Weighted 50 / 20 / 30. This is an estimate, not a measured score |

**Main point: code is no longer the critical path.** What will decide the go-live date is the external work: bank scope decisions, provider contracts (M-Pesa production, SMS), regulator templates, staging infrastructure, UAT and the pen test. Start those now, in parallel with the remaining development rounds.

## 2. Principles

1. **Kenya first, bounded scope.** Demo Bank Kenya (KES, Africa/Nairobi, English + Kiswahili) is the reference configuration. Uganda and Rwanda come after the pilot.
2. **The bank decides scope.** For every gap the bank chooses IMPLEMENT, ACCEPT BOUNDED SCOPE or EXCLUDE. Do not build conditional rounds until the matching decision is recorded.
3. **P0 before P1, P1 before P2.** Universal-banking extras (F21) and customer channels (F18) are post-pilot unless the bank requires them.
4. **Every round follows the repo's definition of done** (see `CLAUDE.md`):
   - approval actions wired through `ApprovalService`
   - migration version pins updated
   - integration and UI tests added, with a full `verify` run
   - a round doc, an `application-progress.md` section and a matrix follow-up line
5. **No invented policy.** Rates, limits, tax rules, report formats and recovery targets come from the bank, never from code defaults.

## 3. Phase overview

| Phase | Goal | Runs in parallel with | Exit |
| --- | --- | --- | --- |
| **0. Unblock** | Version control, MFA, CI, first bank provisioned | Phase 1 | Clean baseline commit, green hosted CI, Demo Bank Kenya ACTIVE on bank V26 |
| **1. Scope decisions** | Bank answers decision register D01–D19 | Phases 0, 2, 3 | Every decision recorded with an owner |
| **2. Functional rounds 9–19** | Close required P0/P1 gaps | Phase 3 | Every IMPLEMENT decision delivered and tested |
| **3. Production engineering** | Staging, pipeline, backup, monitoring, performance, security | Phase 2 | Staging passes the P0 engineering gates (E01–E08) |
| **4. Acceptance (UAT)** | Run the W02–W08 test plan with the bank | Fix loop back into Phase 2 | All required cases PASS, exclusions signed |
| **5. Pilot and go-live** | Cutover, bounded pilot, hypercare | — | Go/no-go signed; pilot stable for the agreed period |

## 4. Phase 0: unblock (first 1–2 weeks)

| # | Task | Why | Done when |
| --- | --- | --- | --- |
| 0.1 | Install Git for Windows and put it on PATH. Commit the work on `cbs-rounds-1-8` in logical commits, push it, and open a PR into `w03-closure-settlement` | `git` is not on PATH on this machine, and earlier progress notes say module work was uncommitted. Nothing is safe until it is pushed | Branch pushed, PR open, no local-only changes |
| 0.2 | First **hosted** run of `verify.yml` and `security.yml` | No hosted CI run has happened yet (gate E05) | Both workflows green on GitHub; reports kept as artifacts |
| 0.3 | Turn on branch protection: PR review and required checks | E05 | `main` and the release branch reject direct pushes |
| 0.4 | Restore MFA (`./scripts/set-local-mfa.ps1 -Mode Mfa`). Create distinct maker, checker, teller, credit, collections, finance and auditor test identities | The password-only exception has expired. Maker-checker testing needs real separate users | Each identity signs in with TOTP |
| 0.5 | Finish Demo Bank Kenya registration and provisioning ([guide](first-bank-kenya.md)). Prepare all bank storage up to V26 | The guide still records it as pending | Bank ACTIVE; audit shows registration and provisioning |
| 0.6 | Rotate every development credential that was ever shared or stored outside `.local/` | E03 | Rotation logged; old secrets revoked |
| 0.7 | Run a Maven dependency scan (OSV-Scanner in CI) | The Maven tree could not be scanned locally | No high or critical findings, or each one triaged |
| 0.8 | Refresh the test catalogue to the current baseline | It records control V37 / bank V22. Several of its 15 NOT IMPLEMENTED gaps are now implemented: closure settlement, chunking, PDF statements, organizations, period close, screening | Catalogue re-baselined to V45/V26 and gap records updated |

## 5. Phase 1: decision register

Send this to the bank's product owner and compliance lead. The "Recommended pilot default" column is a proposal to discuss, not a decision.

| ID | Decision | Recommended pilot default | Blocks |
| --- | --- | --- | --- |
| D01 | First jurisdiction and licensed bank | Kenya, KES | Everything country-specific |
| D02 | Products in the pilot | Savings + current. Term deposits and lending only if the bank needs them at launch | R15, R16 |
| D03 | Languages | English + Kiswahili | R17 |
| D04 | Channels at pilot | Branch/teller + M-Pesa B2C payouts. Add C2B collections only if required. No internet or mobile banking | R13 scope |
| D05 | Volumes: customers, accounts, peak TPS, batch window | Bank to supply | P-06 performance |
| D06 | RPO, RTO and backup retention | Bank to supply | P-04 |
| D07 | Hosting, region and data residency (Kenya Data Protection Act, CBK outsourcing guidance) | Bank compliance to confirm | P-02 staging |
| D08 | Production identity provider and MFA method | Self-hosted Keycloak on PostgreSQL with TOTP, unless the bank has its own IdP | P-03 |
| D09 | SMS and email provider for customer alerts | Bank to choose and contract | R10 |
| D10 | Screening list sources and STR filing format | UN + bank-licensed lists; STR format as required by Kenya's FRC (confirm) | R11 |
| D11 | CBK return templates and reporting calendar | Bank to supply the official templates | R14 |
| D12 | Interest on frozen accounts, and catch-up on reactivation | Bank decision ([chunked batches](servicing-chunked-batches.md)) | R12 |
| D13 | Closing-day pro-rata interest and early-closure penalties | Bank decision ([closure settlement](w03-closure-settlement.md)) | R12 |
| D14 | Dormancy and unclaimed-asset rules | Bank compliance to confirm | R12 |
| D15 | Mandate amount bands ("any one up to X, any two above") | Exclude for pilot unless required | R12 |
| D16 | Credit bureau (licensed CRB) and IFRS 9 approach | Only if lending is in scope | R15 |
| D17 | Legacy data migration or greenfield | Bank to confirm | R19 |
| D18 | Role and approval-limit matrix: who may do what, up to which amount | Bank to supply | R9, UAT |
| D19 | Interbank rails (PesaLink, RTGS/KEPSS) | Exclude from the pilot | Post-pilot |

## 6. Phase 2: functional rounds

Rounds continue the existing numbering (rounds 1–8 are on `cbs-rounds-1-8`). Sizes: **S** = one round, **M** = 1–2 rounds, **L** = 3 or more. "Must" means required for a Kenyan savings/current pilot whatever the bank decides; "Conditional" depends on a decision above.

### Round 9: access governance and approval scope (Must, P0, M)

**Matrix rows:** F03, F04.

**Scope:**
- Bank-scoped roles beyond lending, cash and payments: administration, compliance investigator, treasury, finance/GL and read-only auditor.
- One scope model for every approval action, so each action declares the permission and currency/amount authority it needs.
- A privileged-access review report with periodic re-certification.
- Break-glass access that is time-boxed, dual-approved and alerted.

**Technical notes:**
- Generalise the lending-access pattern (`access` package, `lending-access-ui.js`) into a common role catalogue.
- Next control migration (V46).
- Extend the route access-review test so that every admin route also declares its required permission. The test fails if an action has none.

**Exit:** a test proves that each role is denied outside its scope for every action type. The bank's D18 matrix can be configured without code changes.

### Round 10: customer notifications go-live (Must if alerts are required notices, S–M)

**Matrix rows:** F22, F28, F32.

**Scope:**
- A real `NotificationSender` adapter for the provider chosen in D09, with delivery receipts and webhooks.
- Approved message templates per language.
- Notices for term-deposit maturity, loan due and loan arrears.
- Opt-out handling, recipient verification and a delivery-history view.

**Technical notes:**
- The outbox and retry logic already exist.
- Add a provider binding per bank, like the M-Pesa binding. Callbacks need a signature check and must be added deliberately to the public-route allowlist.
- The production guard should keep refusing the test sender.

**Exit:** a message reaches a test handset or inbox through the provider sandbox, and failures retry and then surface in monitoring.

### Round 11: AML and screening completion (Must, P0/P1, M)

**Matrix row:** F13.

**Scope:**
- Import adapters for real list formats (UN consolidated list and the bank's licensed list) with approval before activation.
- Scheduled periodic re-screening.
- Screening of M-Pesa beneficiaries before dispatch.
- Investigator assignment with case SLAs and overdue alerts.
- STR/SAR export in the regulator's format.

**Technical notes:**
- Builds on control V45.
- Beneficiary screening goes in the payout approval path and fails closed.
- List versions remain immutable.

**Exit:** A13 passes with a real list sample. A beneficiary match blocks the payout. An STR draft exports in the required format.

### Round 12: deposit lifecycle completion (Must, P1, M–L)

**Matrix rows:** F11, F12, F14, F20, F21.

**Scope:**
- **KYC document storage:** encrypted object storage with retention, replacing reference-only documents.
- **Periodic KYC review** driven by risk rating.
- **Dormancy and unclaimed assets** (D14).
- **Deceased-customer workflow.**
- **Closure of accounts that have contract or payment history**, plus a procedure for reversing a closure settlement.
- **D12 and D13 policies** (frozen-account accrual, closing-day interest), if the bank chooses IMPLEMENT.
- **Tiered interest**, if any pilot product needs it.
- **Standing instructions** (recurring internal transfers), if the bank offers them.

**Technical notes:**
- Changes are mainly in the `accounts`, `customers` and `servicing` packages, with a new control migration and possibly a bank migration (dormancy state, document metadata).
- Keep evidence immutable: dormancy and deceased status are approved state transitions, never row updates.

**Exit:** A11, A12 and A14 pass for the bank's chosen scope, and every boundary that is not implemented has a signed EXCLUDE.

### Round 13: M-Pesa go-live (Conditional on D04, L, gated by external work)

**Matrix rows:** F23–F25.

**Scope:**
1. **Real Daraja sandbox.** Credentials, HTTPS callback through staging, and contract tests for success, failure, timeout and duplicate or out-of-order callbacks.
2. **Provider-native statement import**, validated against a redacted real sample.
3. **Collections, if D04 includes them.** C2B and STK Push to fund accounts, with suspense handling for unmatched receipts.
4. **Further payment workflows:** partial returns, cancellation before debit, and handling of insufficient wallet balance.
5. **Production onboarding and certification** with Safaricom.

**Technical notes:**
- The production profile currently refuses dispatch on purpose. Lift that only after certification, through a reviewed configuration change, not a code flag.

**Exit:** A23–A25 pass against the real sandbox, and the production credential is held in the secret store.

### Round 14: regulatory and operational reporting (Must, P0/P1, M once templates arrive)

**Matrix rows:** F09, F19.

**Scope:**
- CBK returns built on the existing approved report definitions, using the D11 templates.
- Deposit and loan registers.
- Teller and cash reports.
- Interest, fee and withholding-tax reports for tax remittance.
- Exception ageing.
- Provisions and allowances report.
- Capital adequacy, once risk weights are supplied.

**Technical notes:**
- Each regulatory field needs an approved source and calculation, reconciled to the GL.
- Reports are versioned and signed.
- Move heavy reports off the posting database (read replica or reporting schema) if P-06 shows contention.

**Exit:** each return for one month reproduces exactly from posted journals and is signed off by bank finance.

### Round 15: lending completion (Conditional on D02/D16, L)

**Matrix rows:** F30–F32.

**Scope:**
- Credit-bureau inquiry and reporting.
- Non-accrual and moratoria.
- Partial write-off and post-write-off recoveries.
- Automated IFRS 9 ECL staging and allowance.
- Fee tax and fee-payment reversal.
- Borrower notices (needs Round 10).
- Removal of the 10,000-event replay limit by adding arrears snapshots.
- Verified document storage (shares the Round 12 store).

**Exit:** A30–A32 pass against the bank's independent lending worksheet (L1).

### Round 16: FX and term-deposit completion (Conditional on D02, M)

**Matrix rows:** F26–F28.

**Scope:**
- Partial term-deposit closure and standing renewal instructions.
- Cross rates and a rate feed (approval still required before rates take effect).
- Valuation corrections.
- Full-bank translation of foreign-currency monetary items, if the bank holds them.

**Exit:** A26–A28 pass.

### Round 17: localization and accessibility (Must if Kiswahili is offered, M)

**Matrix row:** F33. **Gate:** E14.

**Scope:**
- Kiswahili for every screen; newer screens are English-only today.
- Translated receipts, PDF statements, alert templates and error messages.
- An accessibility pass (keyboard, screen reader, contrast) and a low-bandwidth review.
- A native-speaker review.

**Technical notes:**
- Add a UI test that fails when a message key has no translation in an offered language.

**Exit:** A33 passes in English and Kiswahili.

### Round 18: privacy and data protection (Must, P0, M)

**Gate:** E04.

**Scope:**
- A personal-data inventory and a threat model.
- Field-level encryption for sensitive identifiers.
- Masking in non-production exports.
- A retention and archival matrix with legal hold.
- Audit logging of customer-record views.
- Data-subject request handling.
- A review of customer data duplicated between the control and bank databases.

**Exit:** the bank's data protection officer approves the inventory and retention matrix, and the privacy workflows are tested.

### Round 19: data migration and cutover tooling (Conditional on D17, L)

**Gate:** E12.

**Scope:**
- Loaders for customers, accounts and opening balances (posted through an approved migration journal).
- Loaders for live loans with schedules and live term deposits.
- Validation and deduplication reports.
- Delta loads.
- Reconciliation to the legacy trial balance.
- A cutover runbook with a rollback decision point.

**Exit:** two mock migrations reconcile to zero difference, and the cutover is rehearsed end to end.

### Explicitly post-pilot unless the bank requires them

- Internet and mobile banking, USSD, agent banking (F18).
- Interbank rails: PesaLink, RTGS/KEPSS (D19).
- Overdrafts.
- Mandate amount bands (D15).
- Complaints and disputes case management (F20).
- Uganda and Rwanda configurations.
- Universal-banking products (F21).

## 7. Phase 3: production engineering (parallel track)

| # | Work | Gate | Exit |
| --- | --- | --- | --- |
| P-01 | Release pipeline: signed image built from the verified JAR, SBOM, image vulnerability scan, provenance, tagged release candidates | E05 | Release candidate promoted by the pipeline with recorded approvals |
| P-02 | Staging environment on the D07 host: real DNS and TLS, `deploy/staging/up.sh`, secret store, runtime/migration credential split, network isolation | — | Readiness green over HTTPS on staging |
| P-03 | Production identity: Keycloak on PostgreSQL (not dev H2) or the bank's IdP, enforced MFA, key rotation, service identities | E03 | A02 passes on staging |
| P-04 | Backup and recovery: managed PITR or WAL archiving for the control and every bank database, off-host encrypted copies, key escrow, scheduled restore drills, measured RPO/RTO | E06 | A drill meets the D06 targets |
| P-05 | Observability: Prometheus/Grafana dashboards, routed alerts (queue age, failed deliveries, reconciliation differences, security events, capacity), log aggregation, OpenTelemetry tracing, on-call runbooks | E07 | Injected faults raise alerts that operators resolve using the runbooks |
| P-06 | Performance: workload model from D05, **write-path** load tests (posting, funding, cash, batch), 24–72 h soak, measure tenant-row serialization, shared rate limit if more than one instance | E08 | Agreed p95/TPS met with zero balance errors |
| P-07 | Batch scheduling and ops console: automatic preparation of daily runs and period-close readiness (approval stays human), pending-work dashboard | F21 | Daily cycle runs on schedule with human approvals only |
| P-08 | Tenant upgrade orchestration: rolling bank-schema upgrades across all bank databases, mixed-version and failed-upgrade drills | E11 | Failed-upgrade drill causes no tenant corruption |
| P-09 | Invariant and property tests on money paths: generated transaction sequences, leap years, rounding, concurrent spends, replay | E13 | Suite runs in CI |
| P-10 | Security: threat model, internal review, then an **external penetration test** and retest | E01, E03 | No open critical or high findings |

## 8. Phase 4: acceptance

1. Use the refreshed catalogue from task 0.8 on staging, with the real identities from P-03, two fictional banks and two branches.
2. Run the phases in order: **W02** (foundation, P0 first) → **W03** deposits → **W04** cash and servicing → **W05** payments (needs the real sandbox) → **W06** optional products → **W07** reports and languages → **W08** deployment, recovery and end-to-end.
3. Bank finance prepares independent expected results for each financial case. Copying the application's preview is not acceptable.
4. **Fix loop:** a P0 failure stops the phase. Fix it in a short round with a regression test, re-run the affected cases, then continue.
5. Close the phase with signed results, signed exclusions and the evidence pack (release SHA, artifact digest, schema versions).

## 9. Phase 5: pilot and go-live

**Go/no-go checklist:**

- [ ] All required acceptance cases PASS. Every exclusion is signed by the bank owner.
- [ ] No open critical or high defects, and no open critical or high pen-test findings.
- [ ] A restore drill on production infrastructure met the RPO and RTO.
- [ ] Alerts route to a staffed on-call rota. Runbooks have been tested by someone other than the developer (E15).
- [ ] Production credentials (M-Pesa, SMS, database CA) are in the secret store and have been rotated since staging.
- [ ] If migrating: mock migration #2 reconciled, and the legacy trial balance equals the CBS opening trial balance.
- [ ] Regulatory notification or approval for the system change is obtained, as required by the bank's compliance team.
- [ ] Dress rehearsal completed: full business day, day close, month-end close, and backup during the day.

**Pilot shape:** one branch, a limited customer set and conservative transaction limits. Run in parallel with the legacy system if migrating. Hypercare lasts 4–6 weeks with daily reconciliation review. Expand branch by branch after a signed pilot review.

## 10. Critical path and indicative timeline

These are estimates for one developer working with Claude, assuming decisions D01–D18 arrive within about 3 weeks. Provider, pen-test and regulator lead times are outside the team's control and can move every date.

| Weeks | Development | Engineering | External / bank |
| --- | --- | --- | --- |
| 1–2 | Phase 0; start Round 9 | P-01, start P-02 | Send decision register; apply for Daraja sandbox credentials; book pen test |
| 3–6 | Rounds 9, 11, 18 | P-02, P-03, P-04 | Decisions return; contract SMS provider; obtain CBK templates |
| 6–10 | Rounds 10, 12, 14, 17; Round 13 sandbox | P-05, P-06, P-07 | Real sandbox testing; M-Pesa sample statement |
| 8–12 | Conditional rounds 15, 16, 19 | P-08, P-09 | Mock migration #1 (if migrating) |
| 10–16 | UAT fix loop | P-10 internal review | UAT W02–W08; M-Pesa production onboarding |
| 16–18 | Hardening only | Pen test and retest; DR drill | Mock migration #2; dress rehearsal |
| 18–20 | Code freeze | Production cutover | Go/no-go; pilot go-live; hypercare starts |

**Indicative result: a bounded Kenyan pilot in about 4–5 months.** Without lending, FX or migration it could be closer to 3–4 months. Regulatory approval time is not included.

## 11. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Bank decisions arrive late | Rounds 10–19 stall | Send the decision register in week 1. Build the Must rounds (9, 11, 18) first because they need few decisions |
| Provider onboarding delays (M-Pesa production, SMS) | Payments or notifications slip | Apply in week 1. Allow a branch-only pilot with M-Pesa switched on later |
| Key-person dependency (single developer) | Operations risk at go-live (E15) | Runbooks, an ops console, and training a second operator before go/no-go |
| Code not under version control on this machine | Work lost; no traceable releases | Phase 0.1 immediately |
| Throughput limit from tenant-row serialization | Fails peak-load targets | Measure in P-06 before changing lock scope; keep aggregate limits safe |
| Scope creep toward vendor feature parity | Pilot never ships | Hold to section 6's post-pilot list; any change needs a recorded decision |

## 12. Next 10 working days

1. Install Git, commit the current work in logical commits, push `cbs-rounds-1-8` and open the PR (0.1).
2. Get the first hosted CI run green and turn on branch protection (0.2, 0.3).
3. Restore MFA and create the role-separated test identities (0.4).
4. Provision Demo Bank Kenya to bank V26 (0.5).
5. Send decision register D01–D19 to the bank owner.
6. Apply for Daraja sandbox credentials and shortlist SMS providers.
7. Re-baseline the test catalogue (0.8).
8. Choose the staging host and run `deploy/staging/up.sh` there (P-02).
9. Start **Round 9** (access governance).
10. Rotate development credentials (0.6) and book the external pen test.

## 13. Keeping this plan current

- After each round, update the matrix follow-up line and `application-progress.md` as usual. Tick the round's exit criterion here.
- Re-estimate the section 1 percentages after Phase 0, after the decisions return, and after each UAT phase.
- When the bank records a decision, add its date and owner to section 5, and move EXCLUDE items to the post-pilot list.
