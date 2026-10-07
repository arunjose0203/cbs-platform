# CBS capability gaps and production improvement report

Assessment date: 7 October 2026. Target customers: small banks operating in Rwanda, Uganda and Kenya. Technical baseline: Java, Spring Boot and PostgreSQL, with a shared control database and separate bank databases.

The application has an initial banking administration and transaction foundation. It is not yet a production replacement for Oracle FLEXCUBE, Temenos core banking or Infosys Finacle. The largest gaps are complete product servicing, teller cash custody, financial crime controls, external settlement, operational resilience and country-specific acceptance.

The strongest competitive direction is a simpler, demonstrably reliable and economical platform for a defined small-bank segment in these three countries. Broad superiority over every module of established universal-banking suites is not currently supported. It should become a set of measured outcomes rather than a feature-count claim.

## Assessment boundaries

- Local scope comes from the current implementation, recent module documentation and the latest recorded verification: 98 backend tests and 33 UI tests passed. These counts are not code coverage, regulatory acceptance, security assurance or performance certification.
- Vendor scope below comes from published vendor material. It describes advertised capabilities, not independently measured deployment quality. Individual modules, licenses, releases, integrations and country packs vary.
- No vendor sandbox, commercial proposal, implementation contract or comparable performance benchmark was evaluated. Price, implementation speed, customer satisfaction and operating cost cannot be ranked from this evidence.
- The earlier 45–55% functional and 20–30% production estimates were rough planning estimates against a narrower roadmap. They are not parity percentages against these enterprise suites. A defensible percentage needs a frozen scope, weighted requirements and accepted evidence.
- Older project documents contain historical statements superseded by branch controls and CSV export. This report treats those capabilities as initially implemented, not wholly absent.

Status terms: **Initial** means an implemented, limited workflow; **Partial** means a foundation exists but important lifecycle work remains; **Pending** means no complete implementation was established; **Unverified** means acceptance evidence is still required. None means production-certified.

Priority terms: **P0** is a release blocker for any real-money pilot; **P1** is required for the selected initial banking products/channels; **P2** is an expansion or differentiation item; **P3** is broader universal-banking scope that should only be built for a confirmed market need. A conditional P1 becomes a release blocker when that capability is included in the pilot.

## Published competitor capabilities

For the requested T24 comparison, Temenos describes its renaming of T24 to Transact; this assessment also uses its current core-banking portfolio material. An installed T24 release must be compared on its own enabled modules and version. [Temenos naming explanation](https://www.temenos.com/blog/t24-is-now-temenos-transact/).

| Platform | Published scope relevant to this project | What to benchmark |
| --- | --- | --- |
| Oracle FLEXCUBE and related Oracle Banking products | Universal banking across retail, corporate and specialized segments; configurable products; lending, payment and branch offerings across the wider portfolio. Oracle lists digital experience, branch and payments as related products rather than promising that everything is included in one core license. | Product lifecycle completeness, accounting depth, exception handling, multi-entity operations and the effort to deploy a selected small-bank scope. |
| Temenos core banking and broader Temenos portfolio | Core banking, digital banking, payments and wealth products, with cloud, on-premise and SaaS deployment options. Its retail core factsheet covers deposits, accounts, credit/lending and payments. | Parameterized products, country localization, end-to-end servicing, integration effort and upgrade operations. |
| Infosys Finacle core and broader solution suite | Core banking alongside digital channels, payments, cash management, treasury, lending and other components. Its core brochure describes product configuration, APIs, componentized deployment and multi-entity/currency/language capabilities. | Product configuration, branch productivity, API completeness, reconciliation, extensibility and operational scalability. |

Sources: [Oracle FLEXCUBE portfolio](https://www.oracle.com/financial-services/banking/flexcube/), [Temenos products](https://www.temenos.com/products/), [Temenos retail core factsheet](https://www.temenos.com/wp-content/uploads/2025/05/Temenos-Core-Banking-Retail-Factsheet-2025.pdf), [Finacle solution portfolio](https://www.finacle.com/solution/), [Finacle core banking brochure](https://www.finacle.com/content/dam/infosys-finacle/pdf/solutions/finacle-core-banking-brochure-2022.pdf).

These sources establish a broader comparison scope. They do not establish that a particular vendor installation satisfies every requirement below. The backlog is this project's proposed target design, not a claim that every competitor implements every item identically.

## Current application baseline

| Capability | Current state | Material limitation |
| --- | --- | --- |
| Bank tenants and provisioning | Initial | Production tenant lifecycle, migration rollout, capacity isolation and restore operations need validation. |
| Identity and access | Initial | OIDC, MFA/session checks and branch roles exist; privileged access governance remains incomplete. |
| Approval workflow | Initial | Independent approval covers multiple operations; richer approval matrices and administrative segregation remain. |
| Reference data | Initial | Currency, language and calendars exist; a country compliance package is much broader than reference data. |
| Accounting | Partial | Chart, GL accounts, balanced journals, reversals and reports exist; complete financial-period and product accounting remain. |
| Deposit product configuration | Initial | Savings/current parameters and pinned revisions exist; pricing, interest, fees and tax are pending. |
| Customers | Initial | Individual onboarding and manual review references exist; corporate onboarding and automated screening are pending. |
| Accounts and funding | Partial | Opening, received-deposit recording and customer/GL integration exist; complete servicing and closure remain. |
| Operations | Partial | Holds, releases, same-currency transfers, recorded withdrawals and funding reversals exist; complete correction and cash workflows remain. |
| Branch controls | Initial | Approved ownership, branch/staff limits and scoped approval exist; cash funding and broader branch servicing remain. |
| Statements | Initial | Posted-transaction view and bounded CSV export exist; PDF, large exports and delivery are pending. |
| Daily closing | Partial | Accounting close checks and date advancement exist; complete financial batch processing does not. |
| Teller cash, interest, payments, settlement, FX, term deposits and lending | Pending | These are substantial product and accounting domains, not just additional screens. |
| Production operations | Unverified | No completed acceptance baseline for security, realistic load, coordinated restore and failover. |

## Pending functional work

### F01 Tenant administration and country configuration

**Partial; P0/P1.** Add tenant onboarding and retirement runbooks; explicit country and legal-entity configuration; per-bank feature entitlements; controlled configuration promotion; tenant export; audited support access; scheduled bank-schema upgrades; and a failure dashboard for partially upgraded banks. Add resource quotas and test that one bank's workload cannot exhaust resources for all others.

Acceptance: upgrade and restore one bank without exposing another bank's data or interrupting unrelated banks. Demonstrate clean ownership and configuration checks for every target database.

### F02 Access governance and approval policy

**Partial; P0.** Finish maker/checker administration of sensitive staff grants; configurable approval thresholds; multiple approvers where required; temporary delegation with expiry; segregation-of-duty rules; periodic access review; emergency access with independent review; and explicit privileged support sessions. Apply consistent permissions to reports, exports, bulk imports and future channels.

Close the development exceptions: require approved policy coverage before enabling a bank for live transactions; define treatment of unassigned accounts; verify limit coverage for every enabled operation. Extend limits to customer, channel, product and risk categories where bank policy requires them. Distinguish operational transaction limits from credit exposure limits.

Acceptance: a maintained authorization matrix passes positive and negative tests across banks, branches, roles, revoked sessions and concurrent policy changes.

### F03 Customer information and onboarding

**Partial; P0/P1.** Add corporate and SME profiles, beneficial owners, signatories, guardians/minors, joint relationships, customer groups, contact verification and controlled customer merges. Add document storage with malware scanning, encryption, expiry reminders, review queues and access audit. Introduce risk ratings, periodic KYC review and evidence-linked decisions. Connect identity verification and credit-bureau services only through approved providers.

Acceptance: onboarding, correction, rejection, re-verification and closure have traceable reasons and enforce product eligibility without leaking personal data.

### F04 Financial crime and fraud controls

**Pending; P0.** Provide sanctions and politically exposed person screening integration, list-version tracking, re-screening, transaction monitoring, velocity rules, suspicious-pattern alerts, case investigation, dispositions and restricted regulatory-report workflows. Define screening during provider outages and how blocked payments are held, released or rejected. Keep customer complaints and suspicious-activity information appropriately separated.

Acceptance: bank-approved scenarios create cases and enforce the intended transaction outcome; analysts can explain the rule, list version, data and decision used. Use specialist screening/data services rather than inventing sanctions lists.

### F05 Complete product factory

**Partial; P1.** Add effective-dated rates, tiers/slabs, fees, waivers, taxes, eligibility, balance restrictions, channel availability and transaction rules. Model product bundles and negotiated customer overrides. Provide product simulation, previewed accounting effects, controlled rollout and rollback of future configuration.

Acceptance: a product change can be reviewed before activation, and historical transactions remain reproducible using their applicable terms.

### F06 Deposit account servicing

**Partial; P1.** Add joint and business mandates, signing rules, beneficiaries, standing instructions, account renaming and transfers of ownership where permitted, dormancy/reactivation, death/incapacity handling, blocked/unclaimed funds, account closure and final settlement. Add partial holds, expiries and legal restrictions. Implement overdrafts only with approved credit and accounting rules.

Acceptance: an account cannot close with unresolved holds, pending instructions, unsettled balances or required charges. Corrections preserve the original history.

### F07 Teller and cash management

**Pending; P1.** Implement teller tills and vaults by branch/currency; operator assignment; opening authorization; cash allocation/return; cash deposits and withdrawals; teller-to-teller and vault transfers; denomination counts; denomination inventory; counterfeit/suspect cash handling; cash-in-transit; shortages/overages; supervisor review; receipts; and closing reconciliation.

Acceptance: each physical cash movement has one authorized custody event and one balanced accounting effect. Counting cash does not silently adjust the GL. A till cannot close with unexplained differences or unresolved movements.

The existing withdrawal endpoint records an independently completed payout. It is not a cash dispenser, till ledger or proof that Teller Management is complete.

### F08 Interest and pricing calculation

**Pending; P1.** Implement effective-dated rates, tiered and slab calculations, day-count conventions, balance bases, accrual frequency, compounding/capitalization, leap-year behavior, rounding, minimum/maximum charges, fee schedules, waivers, reversals and tax treatment. Define backdated changes and recalculation policy.

Acceptance: calculation examples approved by the bank reconcile exactly to accrual and posting entries. Preserve calculation inputs and policy versions so staff can explain every amount.

### F09 End of day and scheduled processing

**Partial; P1.** Add restartable jobs for interest, fees, tax, standing orders, maturities, dormancy and loan schedules. Define dependencies, cutoffs, bank/branch calendars, missed-day catch-up, rerun rules, queue draining, branch close and bank close. Add month-end/year-end processing and authorized reopening or adjustment procedures.

Acceptance: interruption at any supported checkpoint resumes without double charging, missing accounts or advancing the business date prematurely. Define how channels behave while closing is underway.

### F10 General ledger and accounting control

**Partial; P0/P1.** Complete product-specific accounting templates, suspense and clearing accounts, interbranch due-to/due-from accounting, period controls, accrual adjustments, P&L closure, retained earnings, financial statements and consolidation where required. Define posting date, value date and business date separately. Add drill-down from financial totals to journal and source instruction.

Acceptance: customer subledgers, GL balances, cash custody and external settlement agree; unexplained differences prevent the relevant close. Financial policy and accounting mappings require bank accountant approval.

### F11 Transfers and correction workflows

**Partial; P1.** Add cross-branch transfers, future-dated transfers, standing instructions, beneficiary validation, bulk transfers and complete reversal/correction workflows for transfers and withdrawals. Define fees, cutoffs and approval escalation. Support multi-entity movement only with explicit accounting and ownership rules.

Acceptance: cancellation, rejection, timeout, duplicate submission and partial external completion have distinct outcomes. A correction produces a linked compensating record rather than overwriting a posting.

### F12 Statements and notifications

**Partial; P1/P2.** Add PDF templates, bank branding, customer language selection, statement schedules, tax/interest certificates, accessible documents and asynchronous exports beyond the current 10,000-entry bound. Add verified delivery preferences, consent, templated SMS/email/push, an outbox, retries, provider receipts, deduplication and undeliverable-message handling.

Acceptance: the statement matches posted balances for the selected period, and replaying notification delivery does not disclose information to the wrong recipient or send uncontrolled duplicates.

### F13 External payment orchestration

**Pending; P1.** Build a canonical payment instruction with identifiers, status history, beneficiary checks, screening, limits, fees, account reservations, provider submission, callbacks, timeouts, cancellations, returns and disputes. Add provider credentials, signature verification, replay protection, per-provider circuit breakers and query-before-retry recovery.

Acceptance: a timeout is treated as an unknown outcome until reconciled. Retry cannot create a second debit or payment. Provider approval and certification are separate from coding an adapter.

### F14 Settlement and reconciliation

**Pending; P0 when external payments are enabled.** Add settlement calendars, participant accounts, prefunding, clearing/settlement balances, bank statement or file ingestion, sequence controls, matching rules, partial matches, fees, suspense aging, exception assignment and dual-controlled adjustments. Distinguish customer acceptance, provider acceptance and final settlement.

Acceptance: reconcile internal instructions, external provider records and settlement-bank/GL records. Every unmatched amount has an owner, age and resolution trail.

### F15 FX and multicurrency accounting

**Partial reference-data foundation; P1 when FX is offered.** Add approved rate books, quotes with expiry, buy/sell spreads, cross rates, source and destination amounts, rounding/residual accounts, rate tolerances, position limits, settlement dates, revaluation, realized/unrealized gains and losses, and foreign-currency reporting.

Acceptance: a cross-currency transaction preserves the approved quote and produces balanced accounting in each relevant currency and valuation basis. Currency selection alone does not establish full multicurrency banking.

### F16 Term deposits

**Pending; P1/P2.** Add tenor, value/maturity dates, fixed/variable rates as needed, payout instructions, accrual and tax, renewals, notice periods, partial withdrawals if allowed, early termination penalties, liens and maturity notifications.

Acceptance: opening, daily accrual, maturity, renewal, early closure and corrections are fully accounted for and reproducible.

### F17 Lending

**Pending; P1 when credit is part of the initial offering.** Add application/origination, affordability and underwriting integration, approvals, credit agreements, disbursement conditions, collateral/guarantors, amortization schedules, repayment allocation, floating-rate resets, moratoria, arrears, delinquency, restructures, collections, write-offs, recoveries and bureau reporting. Add credit exposure aggregation and applicable impairment/provisioning rules with specialist review.

Acceptance: reconcile principal, interest, fees, penalties and impairment across normal, overdue, restructured and written-off examples. Scheduling and collection must survive retries and backdated corrections.

### F18 Customer and partner channels

**Pending; P1/P2 by launch scope.** Add customer web/mobile banking, beneficiary management, secure recovery, device/session management, USSD where needed, agent banking, merchant collections, bulk payroll and partner APIs. Consider cards, ATM/POS and cheque clearing only when the bank offers those services; integrate certified processors instead of treating the core as a card switch.

Acceptance: channel authentication, consent, limits, balances and transaction history are consistent with the core. A disconnected branch may capture a draft under a defined policy; it must not silently finalize an unvalidated debit.

### F19 Reporting, regulatory submissions and analytics

**Partial accounting reports; P0/P1.** Add deposit/loan registers, teller reports, arrears and portfolio quality, liquidity and exposure reports, fee/interest/tax reports, exception aging and country-specific returns. Add a reporting store, data lineage, reconciliation to GL and signed/versioned export definitions.

Acceptance: each regulatory field has an approved source and calculation, with reproducible totals and submission evidence. A generic dashboard is not a regulatory reporting engine.

### F20 Customer service and case management

**Pending; P1/P2.** Add complaints, disputes, service requests, document requests, assigned owners, response deadlines, supporting evidence and escalation. Provide a customer relationship view with appropriately restricted access.

Acceptance: a staff member can investigate a failed or disputed transaction from instruction through accounting and external settlement without editing history.

### F21 Broader universal banking

**Pending; P3 unless selected by a bank.** Trade finance and guarantees, syndicated/corporate lending, treasury instruments, securities/custody, wealth management, advanced corporate liquidity, leasing and Islamic product accounting are separate expansion programs. Each requires specialized product, accounting and operational expertise. Do not delay a focused small-bank release merely to match every portfolio label.

## Engineering and production gaps

| ID and priority | Required improvement | Acceptance evidence |
| --- | --- | --- |
| E01 P0 | Tenant isolation across APIs, background jobs, files, caches, exports, search and telemetry; least-privilege runtime and migration roles. | Adversarial cross-tenant tests and reviewed access policies. |
| E02 P0 | Coordinated recovery of control and bank databases, immutable operation identifiers, durable receipt reconciliation and safe retry tooling. | Crash tests around each commit/acknowledgement boundary; no lost or duplicate posting. |
| E03 P0 | Production identity integration, enforced MFA, key rotation, service identities, secret manager, encrypted transport and credential rotation. Rotate any credentials previously shared in development. | Access review, rotation drill and independent security assessment. |
| E04 P0 | Personal-data minimization, encryption, masking, retention, archival, legal holds, access logging and safe test-data generation. Review duplicated customer data in control and bank stores. | Approved data inventory, threat model, retention matrix and tested privacy workflows. |
| E05 P0 | Secure delivery pipeline: protected branches, review rules, automated CI, dependency/secret scanning, artifact signing, release provenance and software inventory. | A release built and promoted by the pipeline with traceable approvals and rollback. |
| E06 P0 | Backups, point-in-time recovery, restore verification, failover topology and independent copies. | Measured recovery-time and recovery-point results covering control and bank data together. |
| E07 P0 | Metrics, tracing, security events, queue age, reconciliation alerts, capacity alerts and staffed incident response. | Fault injection generates actionable alerts and operators follow tested runbooks. |
| E08 P0 | Realistic performance, concurrency, endurance and failure testing. | Agreed workloads, data volumes, hardware and latency percentiles; no balance errors under load. |
| E09 P0 | Bank-led user acceptance and independent accounting validation. | Approved scenarios and reconciled outputs signed by responsible bank owners. |
| E10 P1 | Versioned APIs, contract tests, webhooks, event schemas, transactional outbox, quotas and integration sandbox. | Compatibility tests and safe replay across consumer/provider failures. |
| E11 P1 | Database lifecycle operations, migration rehearsal, rollback/forward recovery and tenant upgrade orchestration. | Mixed-version and failed-upgrade drills without tenant corruption. |
| E12 P1 | Migration tooling: mapping, validation, deduplication, opening balances, documents, historical records, trial conversions, delta loads and cutover. | Reconciled mock migrations and a rehearsed rollback decision point. |
| E13 P1 | Automated rule-boundary and invariant tests, including generated transaction sequences. | Tests for leap years, rounding, concurrent spends, replay, reversals and calendar boundaries. |
| E14 P1 | Full localization and accessibility: translations, date/number formatting, names, documents, keyboards, screen readers and low-bandwidth behavior. | Native-language review and accessibility testing of complete user journeys. |
| E15 P1 | Sustainable operations: ownership, release support, incident SLAs, training, runbooks, support access and upgrade commitments. | An identified team can operate a pilot without developer intervention for routine work. |

Production readiness requires all applicable P0 gates to pass. A high average score cannot compensate for failed tenant isolation, incorrect balances or an untested restore.

## Architecture improvements to evaluate

Keep the Java/Spring Boot modular core while enforcing clear module boundaries. Separate UI routes, policy checks and accounting rules so future mobile or payment channels cannot bypass core authorization. Do not introduce microservices merely to resemble vendor marketing; extract workloads when measured scaling or operating needs justify it.

The current tenant-row serialization helps protect approval correctness, but may become a throughput bottleneck. Measure it before changing lock scope. Any move toward account-level concurrency must preserve safe aggregate bank/branch/staff limits, deterministic lock ordering and deadlock recovery.

The current dedicated customer-liability ledger approach should be tested at the intended account volume. Assess index growth, reporting cost and close-time performance. Do not replace it with a different subledger architecture without accounting and migration evidence.

Formalize the control-to-bank delivery protocol. Give every instruction a stable identity, explicit state transitions, durable receipts and operator recovery actions. A network timeout must not be interpreted as proof that a bank posting failed. Exactly-once business effects require deduplication and reconciliation; a messaging product alone cannot guarantee them.

Keep reporting and large exports away from latency-sensitive posting workloads as scale requires. Add approved extension points and versioned country configurations; avoid maintaining a separate code fork per bank. Define who can author, approve and activate executable rules.

## Rwanda Uganda and Kenya localization backlog

Use independent, versioned country packages layered on the shared core. Each should contain approved calendars, currencies/rounding, product constraints, tax logic, reporting definitions, document requirements, retention rules, provider mappings and translated templates. Country settings must not silently change another bank's historical calculations.

| Market | Initial configuration to prepare | Integration and acceptance work |
| --- | --- | --- |
| Rwanda | RWF, Africa/Kigali; bank-selected English, French and Kinyarwanda coverage. | Confirm licensed-bank obligations and participation model; evaluate approved connections to local settlement and eKash; define reporting, screening, privacy and cross-border data handling. |
| Uganda | UGX, Africa/Kampala; English and bank-selected local-language coverage. | Confirm bank type, payment participation, mobile-money/clearing providers and current interfaces; define reporting, credit-bureau, privacy and outsourcing requirements. |
| Kenya | KES, Africa/Nairobi; English and Kiswahili coverage. | Confirm KEPSS/other required payment participation, approved retail/mobile-money providers, reporting, privacy and outsourcing requirements. |

These language choices are proposed product coverage, not asserted legal requirements. Default configuration must remain subject to the bank's approved operating model.

Kenya's central bank describes KEPSS as its RTGS system; the connector and certification scope must be agreed with the bank and relevant scheme. Rwanda's government describes eKash as its national digital payment system. Uganda's revised oversight framework is a starting point for determining the applicable payment obligations. These sources do not grant scheme membership or certify this application. [CBK payment systems](https://www.centralbank.go.ke/national-payments-system/), [Rwanda eKash announcement](https://www.mod.gov.rw/docs-media/news-detail/rwanda-launches-ekash-advancing-rwandas-cashless-economy-vision), [Bank of Uganda oversight framework](https://bou.or.ug/uploads/Revised_BOU_National_Payment_Systems_Oversight_Framework_2025_698b3e9745.pdf).

Privacy requirements need a country-by-country review, including hosting location, cross-border processing, controller/processor responsibilities, retention and incident procedures. Do not assume either that all offshore hosting is allowed or that all local hosting is mandatory. Start with the relevant authorities' published material and obtain bank/legal approval of the deployment design. [Rwanda DPP law](https://dpo.gov.rw/dpp-law/general-provisions), [Uganda PDPO resources](https://pdpo.go.ug/information-center/resources), [Kenya ODPC legal framework](https://www.odpc.go.ke/data-protection-laws-kenya/).

## How to compete on measurable outcomes

The following are proposed differentiation goals, not achieved results or claims that competitors lack these capabilities.

| Advantage to pursue | Product improvement | Measurement |
| --- | --- | --- |
| Faster small-bank implementation | Reusable country configuration, setup validation, migration tooling and deployment automation. | Time and staff effort from approved scope to reconciled pilot; compare equivalent migrations. |
| Lower total operating cost | Transparent licensing, resource metering, straightforward deployment and limited need for custom code. | Five-year cost including migration, infrastructure, support, compliance, upgrades and integrations. |
| Easier staff workflows | Role-specific screens, searchable accounts, fewer repeated fields and clear recovery guidance. | Task completion time, error rate, training time and support requests on representative tasks. |
| Explainable accounting | Show source instruction, policy/rate version, calculation and journal effect together. | Percentage of tested transactions explainable and traceable without database intervention. |
| Better regional integration experience | Certified connectors, local support and realistic sandbox failure scenarios. | Integration effort, certification outcomes, settlement exceptions and time to resolve them. |
| Safer configuration changes | Simulation, approval, effective dates and impact previews. | Change failure rate and number of changes requiring emergency data correction. |
| Reliable operation on constrained networks | Small responses, resumable user flows and clear pending outcomes. | Completion and duplicate-submission behavior under simulated loss and latency. |
| Predictable recovery | Automated reconciliation, clear runbooks and routine restore drills. | Measured RPO/RTO and unreconciled items after a drill. |
| Easy upgrades without bank forks | Versioned APIs/configuration and tested extension points. | Upgrade effort, downtime and compatibility failures across pilot tenants. |
| Useful AI assistance | Documentation search, draft case summaries and suggested exception matches with human review. | Verified accuracy, information leakage tests and staff time saved. AI must not silently authorize postings or credit decisions. |

Before claiming superiority, run the same bank-defined scenarios on equivalent product scopes and record independent results. Vendor names or modern technology choices alone do not establish a better outcome.

## Proposed delivery sequence

| Stage | Work | Exit condition |
| --- | --- | --- |
| A | Finish access/limit coverage and core accounting corrections; implement teller/vault custody and cash reconciliation. | One branch can open, transact, balance and close with independent review and no unexplained cash difference. |
| B | Interest, fees, tax, product rules, full deposit servicing and restartable daily processing. | A bank-approved deposit lifecycle runs through month-end, correction and closure with exact reconciliation. |
| C | KYC/screening/case controls, one selected payment connection and settlement; notifications and required customer channels. | Onboarding-to-payment-to-settlement scenarios pass certification and bank acceptance. |
| D | FX and term deposits; lending if included in the first bank's scope. | Full lifecycle and exception examples reconcile under approved policies. |
| E | Country reports, migration rehearsals, security/load/recovery acceptance and operating procedures. | All applicable release gates pass and the bank authorizes a bounded pilot. |
| F | Expand country/provider coverage and add selected competitive differentiators or universal-banking products. | Each expansion has its own acceptance and support evidence. |

Security, tenant isolation, accounting tests, documentation and recovery design run throughout every stage. Stage E is final evidence collection and rehearsal, not the first time these subjects are addressed. A deposits-only pilot may omit lending or FX only if the bank explicitly excludes those offerings.

## Acceptance and progress measurement

Replace a single estimated percentage with three tracked measures: functional acceptance, engineering verification and operational release gates. Freeze the initial bank/product/channel scope first. Weight each requirement by effort and risk; calculate accepted functional weight divided by total in-scope weight. Mark excluded enterprise features as out of scope rather than complete.

For each backlog item record: owner, priority, country/product applicability, dependencies, approved examples, accounting effects, permission policy, test evidence, deployment evidence and bank acceptance. Development completion and production acceptance should be separate fields.

Suggested initial acceptance targets to negotiate are: zero unexplained monetary differences; no duplicate business effects in fault/retry scenarios; all applicable permission-negative tests passing; no unresolved release-blocking security findings; and measured availability, latency, peak volume, RPO and RTO against bank-approved thresholds. Numerical service levels remain unagreed and should not be invented as achieved commitments.

## Immediate work order

Start with Teller and Cash Management, while closing mandatory access/limit coverage and reversal gaps. Then implement interest/fees/tax and daily processing. In parallel with product work, prepare financial-crime controls, the country requirements register, recovery design and migration tooling. Provider selection and actual financial limits remain deferred by the user; connector-independent code can proceed, but certification and production policy activation depend on those decisions.

The target is a fully accepted small-bank operating model. Production use should follow successful bank acceptance and release gates, not the completion of a larger number of screens.
