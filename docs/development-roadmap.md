# Development roadmap

## Current increment: Module 02 identity and branch access

The foundation supplies executable service, health checks, packaging and CI.
Module 01 adds tenant registration, legal entities, bank memberships, JWT-protected
administration, per-bank schema provisioning, status changes and transactional audit.
Module 02 adds staff lifecycle, MFA checks, session controls and branch roles, plus
an administration portal. Financial banking transactions are not yet implemented.

The CI workflow is provided but has not been run on GitHub. Deployment rollback,
bank backup restoration, load testing and production hosting remain later work.

## Delivery sequence

| Order | Capability | Status |
| --- | --- | --- |
| 00 | Engineering foundation | Initial implementation |
| 01 | Bank tenant registry and provisioning | Implemented; API and integration tests |
| 02 | Identity provider and bank/branch authorization | Implemented with staff lifecycle, MFA, session controls, branch roles and portal; operator must connect an identity provider |
| 03 | Audit and maker-checker workflow | Planned |
| 04 | Currency, language and business-calendar reference data | Planned |
| 05 | Accounting, balances, holds and posting integrity | Planned |
| 06 | Product factory | Planned |
| 07 | Customer onboarding | Planned |
| 08 | Accounts and deposits | Planned |
| 09 | Internal transfers | Planned |
| 10 | Teller and cash | Planned |
| 11 | Interest, fees and accruals | Planned |
| 12 | Business-day processing | Planned |
| 13 | Statements and notifications | Planned |
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
Full maker-checker remains Module 03 work.

## Definition of done for each financial module

Approved business examples; explicit journal effects; tenant isolation; permission
checks; audit; retry safety; failure recovery; reconciliation; operator guidance.
Record limitations and verification evidence before moving to the next module.
