# Development roadmap

## Current increment: lending validation and loan arrears/collections

The foundation supplies executable service, health checks, packaging and CI.
Module 01 adds tenant registration, legal entities, bank memberships, JWT-protected
administration, per-bank schema provisioning, status changes and transactional audit.
Module 02 adds staff lifecycle, MFA checks, session controls and branch roles, plus
an administration portal. Module 03 adds the Approval Inbox, decisions, history and atomic approval of bank suspension/resumption and staff profile/activation changes. Module 05.3 adds manual journal posting and ledger balances; customer banking transactions remain future work.

The CI workflow is provided but has not been run on GitHub. Deployment rollback,
bank backup restoration, load testing and production hosting remain later work.

## Delivery sequence

| Order | Capability | Status |
| --- | --- | --- |
| 00 | Engineering foundation | Initial implementation |
| 01 | Bank tenant registry and provisioning | Implemented; API and integration tests |
| 02 | Identity provider and bank/branch authorization | Implemented with staff lifecycle, MFA, session controls, branch roles and portal; operator must connect an identity provider |
| 03 | Audit and maker-checker workflow | Initial implementation: inbox, decisions, history; bank suspension/resumption and staff updates |
| 04 | Currency, language and business-calendar reference data | Initial implementation: bank settings, approvals, rounding and calendar previews, user language preferences |
| 05 | Accounting, balances, holds and posting integrity | Chart of Accounts, per-bank ledgers, journals, reversals, balances, reports and approved business-day closing implemented; holds and period/year closing remain planned |
| 06 | Product factory | Initial savings/current product catalogue, currency/minimum rules, liability mapping, approval-controlled activation and immutable revisions implemented; interest, fees and eligibility extensions planned |
| 07 | Customer onboarding | Initial individual profiles, document/review references, duplicate checks, approved status changes and recoverable bank-database delivery implemented; automated verification, corporate/guardian workflows and periodic review remain planned |
| 08 | Accounts and deposits | Account opening, pinned terms, approved received-deposit funding, exact customer balances, dedicated liability ledger integration and recoverable posting implemented; holds/releases, recorded withdrawals and full funding reversals added; closure and broader correction workflows remain planned |
| 09 | Internal transfers | Initial same-bank/entity/currency transfers with independent approval and atomic customer/GL posting |
| 10 | Teller and cash | Custody, customer cash, adjustments, reversals, interbranch transit, denomination inventory and printable receipts implemented; specialist custody and acceptance remain |
| 11 | Interest, fees and accruals | Daily capitalization, explicit fees/taxes, fractional carry and policy approval implemented; advanced servicing remains |
| 12 | Business-day processing | Approved atomic daily servicing batch integrated with closing; scheduling, scalable partitions and period/year close remain |
| 13 | Statements and notifications | Initial customer statement view with date range, opening/closing/running balances; exports and delivery remain planned |
| 14 | External payment integration | Initial Safaricom M-Pesa Kenya B2C sandbox implementation; actual provider acceptance and live certification pending |
| 15 | Settlement and reconciliation | Individual payout evidence/approval and refunds implemented; complete statements, wallet balances, fees and conflict resolution pending |
| 16 | FX conversion and valuation | Rates, atomic conversion/reversal, closing valuation, trading-book P&L, limits and day-close gates implemented; full-bank translation and correction policies pending |
| 17 | Term deposits | Fixed-rate opening, accrual and maturity implemented; renewal/early closure/corrections pending |
| 18 | Lending | Fixed-rate disbursement, equal-principal schedule and repayment implemented; wider credit lifecycle pending |
| 19 | Reporting | Expand with each financial module |
| 20 | Migration and operational readiness | Continuous; acceptance before pilot |

The portal uses an external OpenID Connect provider. Without its configuration,
the portal shows a setup screen and APIs remain closed. CBS manages application
access; passwords, MFA enrollment and directory accounts remain with the provider.
See [Module 03](module-03-approvals.md) for approval coverage and future policy extensions.
See [Module 05.1](module-05-chart-of-accounts.md) for the Chart of Accounts workflow,
configuration storage boundary and remaining accounting work.
See [Module 05.2](module-05-ledger-accounts.md) for bank ledger storage, delivery
recovery and the prerequisites for journal posting.

See [Module 05.3](module-05-journals.md) for posting rules, balance semantics, recovery and first use.

See [Module 05.4](module-05-reports.md) for report semantics, CSV export and limits.

See [Module 05.5](module-05-day-close.md) for closing controls, storage preparation,
date advancement and recovery. See [Module 06.1](module-06-products.md) for deposit
product definitions and approval. See [Module 07.1](module-07-customers.md) for
individual customer onboarding and its manual review boundary. See [Module 08.1](module-08-accounts.md) for approved unfunded account opening and pinned terms. [Module 08.2](module-08-funding.md) now supplies approved funding and customer/GL balance reconciliation. [Account operations](module-08-operations.md) adds holds, withdrawals, funding corrections, same-currency transfers and statements. Scoped posting permissions, mandatory limits and teller/cash increments are now implemented. Continue their acceptance gaps and the compliance workflow described below. Period/year-close controls
remain accounting backlog items. See [application progress](application-progress.md)
for the full delivery status and first end-to-end customer journey.

## Definition of done for each financial module

Approved business examples; explicit journal effects; tenant isolation; permission
checks; audit; retry safety; failure recovery; reconciliation; operator guidance.
Record limitations and verification evidence before moving to the next module.

Current setup and limitations: [inventory and receipts](cash-inventory-and-receipts.md), [daily deposit servicing](deposit-servicing.md).

Manual financial-crime case management is now implemented: [compliance guide](compliance-cases.md). It includes independent decisions and effective restrictions; automated screening and mandatory onboarding verification remain pending. The [enterprise readiness review](enterprise-readiness-review-2026-10-07.md) supersedes older readiness assumptions and lists release blockers across all modules.

First payment provider: [Safaricom M-Pesa Kenya sandbox](payments-mpesa-sandbox.md). Do not advertise live connectivity before credentialed provider acceptance.

Statement workflow: [full normalized M-Pesa import and reconciliation](mpesa-statement-reconciliation.md), including balance controls, accounting adjustments and independent closure. Next payment release gates remain real provider export/contract acceptance, settlement timing and production operational controls.

## Payment lifecycle delivery sequence

Payment Returns, Reversals & Settlement Exceptions now provides confirmed full-return accounting, independently approved settlement timing carries, case history, scoped payment operators and overdue dashboard alerts. Read [payment-lifecycle-exceptions.md](payment-lifecycle-exceptions.md) for the operational workflow and current boundaries. Native M-Pesa statement acceptance and actual Daraja sandbox exercises require external evidence. Continue next with FX & Exchange Rate Management, then Term Deposits and Lending. Remote provider reversal initiation and partial/suspense returns remain distinct payment backlog items.


### 8 October 2026 increment

The first FX, term-deposit and lending flows are implemented. Read [their operator guide](fx-term-deposits-lending.md) before setup; the broad production lifecycle is still incomplete. Next hardening priorities are FX functional-currency valuation and position policy; contract corrections and term renewal/early closure; credit policy, full arrears, restructuring and impairment. These require bank-approved accounting and product rules, in addition to code and acceptance evidence.

See [FX valuation](fx-valuation.md) for configuration, accounting identities, recovery and remaining scope. Next: contract corrections, term-deposit servicing, lending controls, then production acceptance.

## 8 October 2026 — contract corrections and term-deposit servicing

Implemented current-open-day opening/accrual/repayment reversals with independent approval and atomic bank posting, plus explicit early closure, renewal with payout or capitalization, immutable effective terms and in-app maturity alerts. See [operator guide and remaining scope](contract-corrections-servicing.md). Earlier roadmap entries are historical. Next functional priorities are lending assessment, collateral, arrears, restructuring and impairment; production acceptance remains outstanding.

## Lending controls increment — 8 October 2026

The [lending controls guide](lending-controls.md) describes delivered application/review/condition gates and bounded servicing. Next gaps are scoped credit roles, aggregate exposure and bureau integration, full arrears/collections operations, automated impairment, recoveries and jurisdiction-specific acceptance. Earlier roadmap entries remain historical.

## Loan arrears and collections — 8 October 2026

Implemented after isolated lending end-to-end validation. See [operator guide](loan-arrears-collections.md) for exact ageing, posted interest/fee accounting, assignments, promises, escalation and safe retries. Next acceptance work is bank-led lending UAT, scoped collections/credit roles, country product rules, fee tax/corrections and post-write-off recoveries. Security/load/restore/failover remain production gates. Earlier entries are historical rather than current completeness claims.

## 8 October 2026 — lending access increment

Implemented [Bank-Scoped Roles & Approval Limits](bank-scoped-lending-access.md). Next: Production Deployment, Backup & Disaster Recovery, then bank-led acceptance using Rwanda, Uganda and Kenya configurations approved by each bank. Do not treat module count or development test passes as production certification.
