# Development roadmap

## Current increment: Account operations, internal transfers and customer statements

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
| 10 | Teller and cash | Planned |
| 11 | Interest, fees and accruals | Planned |
| 12 | Business-day processing | Accounting date-close foundation in 05.5; interest, fees and scheduled financial batches remain planned |
| 13 | Statements and notifications | Initial customer statement view with date range, opening/closing/running balances; exports and delivery remain planned |
| 14 | External payment integration | Planned |
| 15 | Settlement and reconciliation | Planned |
| 16 | FX conversion and valuation | Planned |
| 17 | Term deposits | Planned |
| 18 | Lending | Planned |
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
individual customer onboarding and its manual review boundary. See [Module 08.1](module-08-accounts.md) for approved unfunded account opening and pinned terms. [Module 08.2](module-08-funding.md) now supplies approved funding and customer/GL balance reconciliation. [Account operations](module-08-operations.md) adds holds, withdrawals, funding corrections, same-currency transfers and statements. Next complete scoped posting permissions and limits before teller/cash. Period/year-close controls
remain accounting backlog items. See [application progress](application-progress.md)
for the full delivery status and first end-to-end customer journey.

## Definition of done for each financial module

Approved business examples; explicit journal effects; tenant isolation; permission
checks; audit; retry safety; failure recovery; reconciliation; operator guidance.
Record limitations and verification evidence before moving to the next module.
