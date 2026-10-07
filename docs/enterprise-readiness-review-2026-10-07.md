# Enterprise readiness review — 7 October 2026

## Assessment

**Release decision: not ready for production banking.** The repository contains a substantial working administration, accounting, deposit and teller foundation, with useful enterprise controls. Functional implementation and passing tests do not demonstrate production capacity, operational resilience, regulatory acceptance or parity with a mature commercial CBS.

This review covers the current working tree, including the uncommitted denomination/servicing increment and the new compliance-case increment. It is a source/configuration review plus local functional regression and browser fixture verification. It is not an independent penetration test, regulatory opinion, financial audit or certification. No defensible production-completion percentage is available without an agreed release scope and measurable acceptance gates.

Confirmed target markets are Rwanda, Uganda and Kenya. Country-specific rules must be approved independently for each bank and jurisdiction. Configurable RWF, UGX and KES support is not country certification or FX conversion.

## Evidence checked

| Evidence | Result and practical limit |
| --- | --- |
| Backend regression | 108 passed, no failures/errors/skips; isolated local PostgreSQL, `.local/compliance-full-tests.log` |
| Final recovery scenarios | 2 passed after adding queued-account and daily-servicing restriction/recovery coverage; `.local/compliance-recovery-tests.log` |
| UI regression | 53 passed; `.local/compliance-ui-tests.log` |
| Dependency audit | npm production dependency audit reported zero known vulnerabilities in the packages checked; `.local/compliance-npm-audit.json`. This does not cover Java dependencies, all development tooling or unknown vulnerabilities. |
| Browser inspection | Fictional compliance list, immutable history and clearance form inspected; `.local/compliance-preview.png`. No real case or clearance submitted. |
| Source review | SecurityConfiguration/AccessService/ProductionGuard; approval and tenant locks; bank database ownership checks; customer/account/funding/cash/servicing delivery; migration constraints; UI errors and request versions; CI definition |
| Runtime | Package succeeded; local app restarted on port 8080 with readiness UP and control V24 applied. Local readiness is not a hosted production acceptance test. |

Local logs and screenshots are ignored development artifacts, not durable release evidence. Archive sanitized evidence with a signed release baseline before bank acceptance. The hosted CI result for this working tree has not been verified.

## Module assessment

“Implemented foundation” means useful controls and functional tests exist. It does not mean the remaining scope is optional for a bank release.

| Area | Controls present | Enterprise gaps / required acceptance |
| --- | --- | --- |
| Foundation and deployment | Java/Spring/PostgreSQL build, Flyway, readiness, CI definition, configuration guard | Hosted pipeline evidence; dependency/SBOM and artifact provenance; staging rollout/rollback; reproducible production topology |
| Bank administration | Tenant lifecycle, approved database references, bank ownership checks, isolated bank storage, audit | Separate migration/runtime privileges, onboarding/decommissioning runbooks, bank-specific backup and restore proof |
| Staff, sessions and permissions | JWT issuer/audience validation, active staff checks, MFA assurance, revocation/session controls; branch operational grants | Fine-grained bank-scoped administration and compliance roles; full least-privilege matrix; privileged access reviews; tested production identity and break-glass procedure |
| Approval Inbox | Maker/checker separation, pinned versions, stale rejection, idempotency and recorded decisions | Configurable multi-level monetary/product approval policies, delegation expiry, escalation and workload controls |
| Reference data and language | Currency precision, rounding, calendars and language preferences | Complete translation of newer workflows, accessibility and browser matrix; approved holiday/tax/reporting configuration for each jurisdiction |
| Chart and ledger accounts | Controlled hierarchy/mappings, independent changes, bank storage, delivery retries | Approved bank/regulatory charts, account lifecycle controls and accounting sign-off |
| Journals and balances | Balanced single-currency entries, exact amounts, immutable receipts, full reversal and replay protection | Value-date/backdate policy, period/year close, specialized correction flows and independently reconciled bank business cases |
| Accounting reports | Trial balance, ledger statements, turnover/reconciliation and CSV | Large-volume report acceptance, consolidated/country regulatory reporting, signed reporting definitions |
| Business-day close | Pending-work/readiness checks, approvals, date locks, next-working-day advancement and recovery | Scheduling, period/year close, controlled reopening, outage catch-up and bank operational acceptance |
| Product Factory | Approved savings/current definitions, pinned terms and revisions, currency/minimum/liability rules | Tiered pricing, eligibility, overdrafts, effective-date migration and complete product lifecycle testing |
| Customer onboarding | Individual profiles, duplicate controls, manual references, approval and recoverable delivery | Corporate/beneficial owners, guardians, verified identity provider integration, privacy/retention, mandatory verification and legacy-customer remediation |
| Compliance cases (new) | Immediate case restriction, append-only evidence, independent clearance, current-profile and due-date checks | Automated sanctions/PEP and transaction screening, scoped investigators, assignment/SLA, full evidence retrieval, regulatory filings and bank-approved review policy |
| Deposit accounts and funding | Numbering, pinned terms, account/customer/GL reconciliation, atomic posting and replay protection | Funded closure, dormant/unclaimed accounts, legal freezes, joint accounts and broader corrections; scoped servicing permissions |
| Customer operations | Holds/releases, withdrawals, same-currency internal transfers and original-linked reversals; mandatory transaction policies | Broader approval policy, cross-currency/interbank flows, bank acceptance of all limits and exceptional operations |
| Teller and cash | Vault/till custody, counted opening/closing, customer cash, independent controls, limits, exceptions, adjustments and reversals | Emergency custody, carrier handling, exchange/reclassification of denominations, write-offs and real teller/printer acceptance |
| Interbranch cash | Dispatch/receipt/return linkage, partial receipt, transit protection and day-close checks | Shipment investigations, carrier tracking and controlled overnight carry/write-off policy |
| Denominations and receipts | Approved catalogue, physical opening stock, quantity checks, stock-aware reversals, stable receipt/reprint records | Hardware printing, extensive receipt retrieval, stock exchange, operational reconciliation and capacity/load acceptance |
| Interest, fees, taxes and accruals | Approved policies, daily capitalization, fixed fees, explicit tax parameters, exact fractional carry and atomic daily batch | Tiered/value-date interest, deferred/monthly capitalization, exemptions, servicing corrections and independently verified country/product calculations |
| Daily servicing | Preview/approval, business-day sealing, unique receipt, retry after lost acknowledgement and statement integration | Batch currently bounded to 1,000 accounts; chunking, checkpoint coordination, throughput/soak proof, scheduling and restricted-account exception workflow |
| Statements and notifications | Customer balance/statement views and bounded backend CSV export | Complete portal export/delivery, PDF, verified recipients, notification outbox, retries and receipts; richer servicing component presentation |
| Payments and settlement | Internal accounting foundation only | Selected provider contracts, sandbox integration, callback authentication, returns, timeout recovery, settlement matching and exception handling |
| FX, term deposits and lending | Currency definitions only; these product engines are not implemented | Rates/conversion/revaluation; maturity/renewal; credit workflow/schedules/repayment/arrears and bank-approved accounting |
| Country reports, migration and channels | General administrative UI and accounting reports | Country reporting packs, ETL/dry runs, opening-balance reconciliation, customer channels and formal bank UAT |
| Operations and resilience | Health/readiness, logs and recoverable instruction records | Alerting/SLOs/on-call runbooks, isolated secret management, measured load/restore/failover and coordinated control/bank recovery |

## Concrete issues addressed in this increment

1. **Compliance after approval:** introduced current-case eligibility checks and ensured queued account delivery checks the customer before the first bank posting. A later restriction can no longer be missed by that delivery path.
2. **Daily-servicing eligibility race:** recheck customer eligibility before an unposted daily batch. Receipt-first recovery acknowledges an already committed batch despite a later restriction, avoiding duplicate postings and indefinite false failures.
3. **Cash quantity capacity:** validate the resulting denomination quantity against the database capacity before approval, including reversal paths. Reject impossible inventory rather than discovering the capacity error during posting.

Tests exercise independent review, stale evidence, duplicate requests, cross-bank lookup, unauthenticated/non-admin access, profile changes, immutable history, queued operations, atomic failure and lost acknowledgements. These are bounded scenarios, not exhaustive assurance.

## Production blockers in priority order

| Priority | Required work | Acceptance evidence |
| --- | --- | --- |
| P0 | Establish the first licensed bank's launch scope and country-specific policies | Named accountable bank owners; approved products, accounting, limits, review/retention rules and acceptance cases |
| P0 | Complete least privilege and database privilege separation | Bank-scoped administrator/compliance roles; negative permission tests; non-owner runtime roles; production MFA; audited privileged access |
| P0 | Secure deployment and credentials | Production profile enforced by deployment; secret manager/rotation; TLS with verified database identity; network isolation; no development credentials in public hosting |
| P0 | Customer verification and financial-crime operations | Mandatory onboarding gate/backfill; verified provider or approved manual procedures; sanctions/PEP/monitoring lifecycle; documented investigation and reporting acceptance |
| P0 | Recovery and financial correctness at launch scale | Coordinated control/bank restore and PITR drill; reconciled receipts/customer balances/GL/day state; measured RPO/RTO and failover evidence |
| P0 | Independent security and bank acceptance | Threat model, ASVS verification, Java and UI dependency scans, SAST/DAST, penetration-test remediation and bank UAT sign-off |
| P1 | Scale storage and processing | Replace unmanaged per-operation bank connections with bounded lifecycle/pooling as appropriate; test tenant-lock contention and control-pool sizing; scalable batch design and agreed peak/soak results |
| P1 | Complete offered product/service rules | Bank-approved interest, tax, closure, dormant/exception rules; financial examples and boundary tests; no unsupported feature advertised |
| P1 | External payments and reconciliation if included at launch | One selected provider certified in sandbox, signed callbacks, returns, suspense and settlement breaks reconciled end to end |
| P1 | Monitoring and operations | Queue-age/reconciliation/security alerts, recovery runbooks, operator training and incident drills |
| P2 | Expand usability and product coverage | Full translations/accessibility, searchable selectors, large history/receipt retrieval, additional channels/providers and specialized products |

The current control datasource pool is small (configured maximum 5), bank connections are opened through DriverManager, and tenant-level locking serializes sensitive activity. Those choices simplify correctness but require measurement and design work before promising bank-scale throughput. No transactions-per-second claim is supported.

Database triggers protect normal application writes, but a privileged database owner can bypass them. Separate privileges and independently retained audit evidence are required. Immutable application tables alone are not a tamper-proof archive.

## Next implementation sequence

Continue the user's existing order: close remaining access/cash and servicing gaps, deepen verification/case management, then integrate one selected payment provider with settlement/reconciliation. FX, term deposits and lending follow the first bank's actual offering. Country reporting, migration, channels and production acceptance remain explicit release gates. Work on shared infrastructure can continue before a provider is selected; real integration/certification cannot be claimed without its specifications and sandbox.

## Reference frameworks

- [OWASP Application Security Verification Standard](https://owasp.org/projects/asvs) provides a security verification framework. No ASVS level is claimed as passed by this review.
- [FATF Recommendations](https://www.fatf-gafi.org/en/publications/Fatfrecommendations/Fatf-recommendations.html) and [PEP guidance](https://www.fatf-gafi.org/en/publications/Fatfrecommendations/Peps-r12-r22.html) inform requirements discovery. Local requirements and bank policies need jurisdiction-specific specialist approval; a manual case page is not proof of conformity.

This report makes no claim of being better than Oracle FLEXCUBE, Temenos or Finacle. A meaningful comparison requires a defined use case, verified feature coverage, total operating cost, bank-scale benchmarks and customer acceptance evidence.

## Subsequent increment: M-Pesa sandbox payments

After this review, the first Safaricom M-Pesa Kenya B2C sandbox workflow was added. It implements approved accounting, one-time dispatch claims, asynchronous observations, manual statement-line matching and approved refunds. It does not remove the payment production blockers above. Actual provider contract tests, full statement/balance/fee reconciliation, beneficiary screening, scoped payment roles and live certification are still required. See [exact scope](payments-mpesa-sandbox.md) and verification.md for the later test results.
