# Production release gates — African regional CBS

Updated 7 October 2026. Release status: **NOT PRODUCTION READY**.

The user authorized continued implementation of the full roadmap without per-module permission requests. Target region is Africa. A first jurisdiction, licensed operator, payment providers, production infrastructure, transaction-volume targets and recovery objectives have not been specified. Shared core implementation can continue; jurisdiction-specific acceptance and provider certification cannot be inferred from a regional target.

## Scope and evidence gates

| Area | Current evidence | Release requirement |
| --- | --- | --- |
| Deposit posting | Local automated funding/debit/hold/transfer/reversal and recovery tests | Bank-approved business cases, limits and independent acceptance |
| Access | Platform-admin workflows, staff/session/MFA checks | Scoped bank/branch operational permissions, privileged access review, working production MFA |
| Deployment | Packaged Spring Boot application, CI workflow | Hosted CI evidence, signed release baseline, staging rollout and rollback drill |
| Production configuration | ProductionGuard rejects local HTTP identity, MFA exceptions, bootstrap admin and database URLs without verify-full | Deploy with production profile, real secret store, trusted database CA, TLS ingress and tested network isolation |
| Backups | No restore drill demonstrated | Independent backups of control and every bank database, point-in-time recovery, coordinated consistency checks and measured restore |
| Performance | Functional concurrency/retry tests only | Agreed peak TPS, database scale, soak and failover tests |
| Monitoring | Health/readiness, application logs, operation retry state | Alerts for queue age, failures, recon differences, security events and capacity; staffed runbooks |
| Compliance | Configurable country/currency/calendar; manual customer review | Country-specific onboarding/screening, privacy, retention, accounting and reporting requirements approved by bank specialists |
| Payments | No connected payment rail | Provider contracts, sandbox credentials/specifications, signatures, idempotency, returns, settlement and certification |
| Products | Savings/current setup, pinned terms | Interest, fees, tax, overdraft, term deposits and lending implementation/acceptance |

## Remaining development sequence

1. Bank/branch posting authorization, approval limits and reversal coverage.
2. Teller tills, cash movements, denomination counts, limits and balancing.
3. Effective-dated interest/fee policies, accruals, tax and batch checkpoints.
4. Complete daily processing, period/year close, statement exports and notification delivery.
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
