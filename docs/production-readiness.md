# Production release gates — African regional CBS

Updated 11 November 2026. Release status: **NOT PRODUCTION READY** (see the [production-readiness round](#production-readiness-round--11-november-2026) for what changed).

The user authorized continued implementation of the full roadmap without per-module permission requests. Target markets are Rwanda, Uganda and Kenya. The first launch jurisdiction, licensed operator, payment providers, production infrastructure, transaction-volume targets and recovery objectives still require selection. Shared core implementation can continue; jurisdiction-specific acceptance and provider certification cannot be inferred from a regional target.

## Scope and evidence gates

| Area | Current evidence | Release requirement |
| --- | --- | --- |
| Deposit posting | Local automated funding/debit/hold/transfer/reversal and recovery tests | Bank-approved business cases, limits and independent acceptance |
| Access | Staff/session/MFA checks, scoped branch operations, bank/branch lending roles, immutable grant review history and currency/operation approval limits; automated access review of every route; per-client rate limiting and hardened browser headers | Finer bank-scoped administration/compliance roles, privileged access review, production MFA |
| Deployment | Docker/Caddy staging templates, offline migration entry point, restricted runtime privileges, one-command bring-up (`deploy/staging/up.sh`), CI image build and Compose validation, weekly dependency updates and vulnerability scans | Hosted CI evidence, signed release baseline, staging rollout and rollback drill |
| Production configuration | ProductionGuard rejects local HTTP identity, MFA exceptions, bootstrap admin and database URLs without verify-full | Deploy with production profile, real secret store, trusted database CA, TLS ingress and tested network isolation |
| Backups | Encrypted coordinated backup and isolated full-schema restore drill passed with fictional funded customer, journals, approvals and pending deliveries; see [recovery runbook](production-deployment-recovery.md) | Independent backups of control and every bank database, point-in-time recovery, coordinated consistency checks and measured restore |
| Performance | Concurrency/retry tests, opt-in 1,050-account servicing volume run, hot-query benchmark with indexes (V44/bank V26) and a staging load probe | Agreed peak TPS, database scale, soak and failover tests |
| Monitoring | Read-only database/queue checks, authenticated backup/restore evidence, Prometheus output and sample alert rules; local failure tests pass | Alerts for queue age, failures, recon differences, security events and capacity; staffed runbooks |
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

## Next implementation priority

Production Deployment, Backup & Disaster Recovery follows the bank-scoped lending access increment. Build repeatable deployment and coordinated backup/restore tooling, then measure recovery in an isolated staging environment with bank-approved RPO/RTO. Acceptance remains open until actual restore/failover evidence and bank-led financial/security testing exist. See [lending access setup](bank-scoped-lending-access.md).

## Production-readiness round — 11 November 2026

This round hardens what already exists; it does not add banking features. Evidence is local and automated. Staging on a real host, a hosted CI run, penetration testing and bank-agreed volume targets are still open.

### Security

- **Endpoint access review, automated.** An integration test walks every mapped route (262 method/path pairs, 171 of them under `/api/v1/admin/`). Each one must return 401 with no token and with a forged token, and every admin route must return 403 for an authenticated non-administrator. Only five routes are public: the portal shell (`/`, `/admin`, `/admin/`), `/portal/config` and the M-Pesa callback, which checks its own per-payment token (a guessed token gets a 4xx). If a new public route is added, or a public route disappears, the test fails until the allowlist is updated on purpose. Health probes and static portal files are covered by the security configuration and `FoundationHttpTest`.
- **Rate limiting.** `RateLimitFilter` runs inside the security chain before any token is parsed. Each client address has two budgets per application instance: 600 requests a minute overall, and 20 failed authentications (401) a minute. Over either budget, the response is `429` with `Retry-After` and code `RATE_LIMITED`. Health probes are exempt, and tracked clients are capped so memory stays bounded. Settings live under `cbs.rate-limit.*`. The production guard refuses `cbs.rate-limit.enabled=false`. The production profile now trusts forwarded headers (`server.forward-headers-strategy=native`), so behind Caddy the limits apply to real clients rather than the proxy. The guard refuses a non-loopback bind without it. Caddy replaces any client-supplied `X-Forwarded-For`. The app container publishes no port, so only the edge can reach it.
- **Browser headers.** These are on every response, including 401 and 429: CSP (adds `form-action 'self'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a restrictive `Permissions-Policy` and `Cache-Control: no-store`. HSTS (one year, including subdomains) is sent on HTTPS by both the app and Caddy. Caddy also drops its `Server` header and refuses request bodies over 8 MB. The largest legitimate body is a 1 MiB statement CSV.
- **Dependencies.** `npm audit` reported 0 vulnerabilities on 11 November 2026. The Maven tree could not be scanned from this workspace because the vulnerability databases are not reachable here. The new `Dependency security` workflow runs `npm audit --audit-level=high` and OSV-Scanner (Maven, npm and pip) on each pull request, on pushes to `main` and every Monday. Dependabot opens weekly update pull requests for Maven, npm, pip, GitHub Actions and the Docker base image. The scan results stay in the job log, because code-scanning upload needs GitHub Advanced Security on private repositories.

### Performance

A daily-limit check on every posting summed a bank's **entire history** with a sequential scan. Per-account lookups (closure-settlement guard, operation history, statement movements from FX and contract entries) had no index either. Control **V44** and bank **V26** add the missing indexes. Both are plain `CREATE INDEX IF NOT EXISTS`: build them `CONCURRENTLY` first on a bank that is already live with large tables.

These are measured times on synthetic data: 1,000,000 account operations and 200,000 fundings across 50 banks and 20,000 accounts, PostgreSQL 16. The exact application SQL is in `ops/perf/hot_queries.sql`.

| Query | Before | After |
| --- | --- | --- |
| Daily limit, account operations | 82 ms (parallel seq scan) | 0.7 ms (index scan) |
| Daily limit, funding | 24 ms | 0.4 ms |
| Closure-settlement guard | 77 ms | 0.19 ms |
| Account operation history (50 rows) | 63 ms | 0.13 ms |

The "before" times grow with history; the "after" times grow only with one day's volume or one account's history. `ops/perf/load_probe.py` is a concurrent read-only probe for staging. It reports p50/p95/p99, throughput and status mix, and exits non-zero when a p95 or error-rate threshold is missed. It reads the token from `CBS_TOKEN`. The opt-in servicing volume test (`CBS_VOLUME_TESTS=true`) still reconciles 1,050 accounts exactly.

### Deployment and CI

- `deploy/staging/up.sh [--env-file …] [--skip-migrations]` runs these steps in order: preflight, stop writers, run the migration job, reapply runtime grants, start app and edge, then check readiness over HTTPS. Grants must be reapplied by the database owner, so the script stops after the migrations unless `CBS_PRIVILEGES_HOOK` names an executable that does this. Nothing then starts on stale grants.
- `runtime.example.yml` documents `cbs.servicing.chunk-size`, `cbs.rate-limit.*` and the notification sender. Leave the sender unset until a provider is contracted; alerts then stay `PENDING`. The app container runs in UTC.
- The `Verify` workflow now pins `TZ=UTC`, cancels superseded runs, keeps test reports when a run fails, builds the release image from the verified JAR and validates the Compose stack. The pom pins the test JVM to UTC (`argLine`). A developer machine whose zone the database does not recognise, such as the legacy alias `Asia/Calcutta`, previously failed every database test.
- End-to-end check with the packaged JAR behind a local Caddy using the same edge settings. Twenty-four unauthenticated requests, each with a different forged `X-Forwarded-For`, gave 20 × 401 and then 429: spoofed addresses do not reset the budget. 429 responses kept the hardening headers. Health stayed at 200 while the client was throttled. A 9 MB body was refused with 413 at the edge, and a guessed M-Pesa callback token got 409. The load probe ran against readiness (which includes a database check) through the edge for 15 s with 8 workers: 11,278 requests, 0 errors, 752 req/s, p50 9.6 ms, p95 19.9 ms, p99 27.6 ms. The probe and server shared one workspace machine, so this is not a capacity figure.
- A restore drill on 11 November 2026 (`ops/tests/recovery_drill.py`, all control and bank migrations including V44/V26, run once with SQL files and once through the packaged JAR's offline migration launcher) returned PASS. It covered a full-schema encrypted backup and restore, verified runtime privileges, restored a 100 KES customer balance, preserved two pending postings and a lost acknowledgement, and verified the configuration export. The restore took 2.2–2.6 s for this small fixture.

### Still open for release

A hosted CI run on GitHub. Build of the pinned release image and a vulnerability scan of that image. A staging host with real DNS, TLS and identity. A load test at bank-agreed peak volumes and a soak test. An external penetration test. A rate limit shared across instances if the application is scaled horizontally. Measured RPO/RTO signed by the bank.

