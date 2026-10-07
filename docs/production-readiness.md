# Production release gates — African regional CBS

Updated 7 October 2026. Release status: **NOT PRODUCTION READY**.

The user authorized continued implementation of the full roadmap without per-module permission requests. Target markets are Rwanda, Uganda and Kenya. The first launch jurisdiction, licensed operator, payment providers, production infrastructure, transaction-volume targets and recovery objectives still require selection. Shared core implementation can continue; jurisdiction-specific acceptance and provider certification cannot be inferred from a regional target.

## Scope and evidence gates

| Area | Current evidence | Release requirement |
| --- | --- | --- |
| Deposit posting | Local automated funding/debit/hold/transfer/reversal and recovery tests | Bank-approved business cases, limits and independent acceptance |
| Access | Staff/session/MFA checks, scoped branch operations and mandatory limits | Finer bank-scoped administration/compliance roles, privileged access review, production MFA |
| Deployment | Packaged Spring Boot application, CI workflow | Hosted CI evidence, signed release baseline, staging rollout and rollback drill |
| Production configuration | ProductionGuard rejects local HTTP identity, MFA exceptions, bootstrap admin and database URLs without verify-full | Deploy with production profile, real secret store, trusted database CA, TLS ingress and tested network isolation |
| Backups | No restore drill demonstrated | Independent backups of control and every bank database, point-in-time recovery, coordinated consistency checks and measured restore |
| Performance | Functional concurrency/retry tests only | Agreed peak TPS, database scale, soak and failover tests |
| Monitoring | Health/readiness, application logs, operation retry state | Alerts for queue age, failures, recon differences, security events and capacity; staffed runbooks |
| Compliance | Configurable country/currency/calendar; manual customer review and independent compliance cases | Country-specific onboarding/screening, privacy, retention, accounting and reporting requirements approved by bank specialists |
| Payments | Initial M-Pesa Kenya sandbox adapter and manual per-payout reconciliation; not connected to an actual provider account | Provider contracts, sandbox credentials/specifications, signatures, idempotency, returns, settlement and certification |
| Products | Savings/current setup, pinned terms, daily capitalization/fees/tax and batch controls | Advanced servicing, bank rule acceptance, overdraft, term deposits and lending |

## Release completion sequence

Initial limits, teller/inventory/receipts, daily servicing and manual compliance cases are implemented. The items below include their remaining scope and acceptance, not a claim that those areas have no implementation. See the [dated enterprise review](enterprise-readiness-review-2026-10-07.md) for the current module-by-module evidence.

1. Bank/branch posting authorization, approval limits and reversal coverage.
2. Teller tills, cash movements, denomination counts, limits and balancing.
3. Effective-dated interest/fee policies, accruals, tax and batch checkpoints.
4. Complete mandatory verification/screening and case operations, daily processing, period/year close, statement exports and notification delivery.
5. Provider-specific payments and external settlement/reconciliation.
6. FX rates, cross-currency posting, valuation and gains/losses.
7. Term deposits and lending with approved product rules, schedules and arrears.
8. Operational/regulatory reports, migration tooling, privacy and archival controls.
9. Security assessment, volume testing, restore/failover, hosted release and bank pilot acceptance.

## Production profile

Activate spring.profiles.active=production. Supply HTTPS issuer/JWKS/portal URLs, audience/client ID and control/bank database credentials through a managed deployment secret store. Database URLs must use sslmode=verify-full (optionally channelBinding=require); install the database CA trust appropriate to the PostgreSQL driver. No local MFA exception or automatic bootstrap administrator is allowed. Existing staff must be provisioned through an audited deployment procedure before launch.

The guard is a configuration check, not a substitute for security review. Production ingress TLS, secret rotation, database-role separation, backup retention, least privilege and deployment topology still require implementation and evidence. Never copy .local development credentials/configuration into a production deployment.

## Recovery outline to validate in staging

Stop new submissions, preserve logs and operation IDs, and determine whether each bank receipt committed before retrying. Restore control and bank databases to a coordinated recovery point. Reconcile operation receipts, pending deliveries, customer positions, GL balances and business-day state before reopening. A control-only rollback can lose approved instructions while bank postings remain committed; do not blindly replay or discard them. Record measured RPO/RTO and operator sign-off. This procedure is not yet a demonstrated disaster-recovery capability.

The new [M-Pesa sandbox increment](payments-mpesa-sandbox.md) does not satisfy the payment production gate. It rejects production-profile dispatch and requires actual provider acceptance before any live expansion.

The [statement reconciliation module](mpesa-statement-reconciliation.md) adds normalized CSV evidence and controlled accounting/closure. It does not satisfy provider-native certification, original-evidence authenticity, scalable import, timing/net-settlement or bank production acceptance gates.

### Payment returns and exception readiness

V27 implements bank-scoped payment roles, approved timing carries, investigations and full customer returns with retry recovery. Local automated evidence does not satisfy provider certification. Release gates still include actual export-format validation, Daraja sandbox acceptance, reconciliation policy/deadline approval, production provider credentials/contract, account restriction/suspense policy, remote reversal and partial-return scope, outbound escalation and bank finance sign-off. See [payment lifecycle](payment-lifecycle-exceptions.md).
