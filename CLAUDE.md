# CBS platform — notes for Claude Code

Core banking system: Java 21, Spring Boot 4.1.1, Jackson 3, Flyway, PostgreSQL, plain-JS portal in `ui/`.
Developed on Windows (PowerShell). Status and history: `docs/application-progress.md`.

## Build and test

```powershell
Set-ExecutionPolicy -Scope Process Bypass          # once per terminal, so scripts/*.ps1 run
npm.cmd ci --prefix ui --ignore-scripts            # before any Maven build (the UI is bundled into the JAR)
npm.cmd test --prefix ui                           # UI unit tests (node --test ui/test/*.test.mjs)
./scripts/mvn.ps1 --batch-mode -DskipTests package # build target/cbs-platform-0.1.0-SNAPSHOT.jar
./scripts/mvn.ps1 --batch-mode verify              # unit + HTTP tests (DB integration tests skipped)
python -m unittest discover -s ops/tests           # operations tooling tests
```

Database integration tests (most of the coverage) need a PostgreSQL role that can create roles
and databases, because every test bank gets its own database:

```powershell
$env:CBS_INTEGRATION_TESTS='true'; $env:CBS_DB_URL='jdbc:postgresql://127.0.0.1:5432/cbs_test'
$env:CBS_DB_USER='cbs_test'; $env:CBS_DB_PASSWORD='<local test password>'
./scripts/mvn.ps1 --batch-mode verify "-Dtest=TenantAdministrationIntegrationTest#someTest*"
```

Tests always run in UTC (pom `argLine`). Never point integration tests at the development
database used by `scripts/run.ps1`.

Run locally: terminal 1 `./scripts/run-local-identity.ps1` (Keycloak :8180), terminal 2
`./scripts/run.ps1` (CBS :8080), portal http://127.0.0.1:8080/admin/index.html.
`.local/` and `.tools/` hold local secrets and tools; never commit or print their contents.

## Layout

- `src/main/java/com/cbs/<area>/` — one package per area (accounts, cash, payments, compliance, reporting, …),
  usually `*Models` (records), `*Service`, `*Controller`, and `Bank*Store` for writes to a bank database.
- `src/main/resources/db/migration/` — control database (`platform` schema), `V<n>__name.sql`.
- `src/main/resources/db/bank-migration/` — each bank's own database (`bank` schema).
- `src/test/java/com/cbs/tenancy/TenantAdministrationIntegrationTest.java` — main end-to-end suite; reuse its
  helpers (`fundingFixture`, `onboard`, `person`, `postOperation`, `postCash`, `approveHttp`, `assertCode`).
- `ui/src/<area>-ui.js` — portal modules, each with a test in `ui/test/<area>.test.mjs`; nav entries in
  `src/main/resources/static/admin/index.html` and `ui/src/app.js`; messages in `ui/src/model.js`, French/Swahili in `ui/src/i18n.js`.
- `docs/` — one doc per feature round, plus `application-progress.md` and the functional-gap matrix.

## Rules that are easy to break

- **Maker-checker.** Any change that affects money, customers, limits or policy goes through `ApprovalService`.
  A new action needs: the `Action` enum value, the `approval_request_action_check` constraint in a new migration,
  a submit method, a preview branch (`before`/`after`/`label`/`bankId`), an apply `case`, and the service wired
  by setter injection (`@Autowired void x(...)`). The maker can never approve their own request.
- **Optimistic versions.** Requests carry `expectedVersion` (the bank's version); `tenants.change(...)` bumps it.
  Background jobs must not bump it.
- **New migrations** must update the version pins: `DatabaseIntegrationTest` (control), the `schemaVersion`
  assertions in `TenantAdministrationIntegrationTest` (bank) and `ops/tests/recovery_drill.py` (bank).
- **New test bank databases**: add the suffix to `TestDatabases` in three places (the array, a `bind(...)` line,
  and the cleanup regex in `close()`).
- **Every Spring component** that needs the database is annotated
  `@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)`, or the
  database-free HTTP tests fail to start.
- **Evidence is immutable.** Ledgers, journals, audit, screening lists and monitoring history are protected
  by triggers: correct with new rows (reversals, adjustments), never UPDATE/DELETE.
- **Idempotency.** POSTs that create approvals take an `Idempotency-Key` header; the UI reuses the key for an
  identical body. Bank-side receipts compare `payload=?::jsonb`.
- **Money** is `BigDecimal`/`NUMERIC`, passed as strings in JSON; never `double`. Amounts are never truncated in output.
- **Security.** Every API route requires authentication; the access-review test fails if a public route appears.
  Do not echo request payloads or secrets in errors or logs.
- **Compatible payloads.** New optional fields on stored records use `@JsonInclude(NON_NULL)` plus a secondary
  constructor, so earlier payloads stay byte-identical.

## Working agreements

- Work on a branch; keep `main` releasable. Small commits with a clear message.
- Before finishing: UI tests, `verify`, and the integration tests for the area you touched.
- Each feature round updates its doc in `docs/`, adds a section to `docs/application-progress.md`
  and a follow-up line in the functional-gap matrix.
