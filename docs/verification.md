# CBS verification

## Development sign-in recovery

On 2026-09-23, the local provider still used its password-only client flow after
the CBS development MFA exception had expired. The named administrator was
enabled with no outstanding required actions. The existing temporary exception
was renewed for eight hours and CBS restarted to load it.

Expired exceptions now return `LOCAL_MFA_EXCEPTION_EXPIRED`; the portal preserves
mapped CBS sign-in errors while hiding raw provider callback details. Four
targeted MFA-policy tests and all six UI tests passed. Tests confirm that expired
password-only access stays denied while valid MFA evidence still reaches normal
staff checks. Real password entry remains a user step.

## Module 04 reference data

On 2026-09-23, **52 backend tests passed with zero failures, errors or skips**
against the isolated local PostgreSQL test environment. All five UI unit tests
also passed. Coverage includes pending approval before settings take effect,
exact decimal rounding with zero/two/three-place currencies, signed amounts,
functional-currency protection, language defaults, direct-update bypass prevention,
bank isolation, stale approvals, calendar holidays/weekends, leap dates,
forward-only business dates and authenticated user preferences.

Browser checks on isolated fixtures verified the settings layout, currency
submission without immediate effect, language settings, Swahili and French labels,
and the working-day preview. The fixture server does not contact Neon or the
identity provider. Packaging succeeded. See [Module 04](module-04-reference-data.md)
for scope, API, initialization defaults and remaining operator acceptance steps.

The packaged application applied Flyway V5 successfully to Neon, restarted on
localhost:8080, and returned readiness `UP`. The updated Bank settings navigation
is visible in the live portal; unauthenticated catalogue access returns HTTP 401.
Live sign-in and a real two-person approval remain operator acceptance steps.

## Module 03 approval workflow

On 2026-09-22, **46 backend tests passed with zero failures, errors or skips**
against isolated local PostgreSQL databases. Coverage includes approved bank
suspension/resumption through the HTTP API, deferred staff deactivation,
idempotent submissions and decisions, self-approval prevention, issuer/role
isolation, stale versions, rejection/cancellation, concurrent approval, immutable
audit events, disabled makers and transaction rollback after a business failure.
All three UI model tests passed and the application packaged successfully.

Browser checks using the isolated fixture server verified the inbox, comparison
page, approval and reviewer history, My Requests, maker-only cancellation,
rejection, history and request-type filtering. These fixtures did not change
live bank data or bypass the running application's authentication.

The packaged service applied Flyway V4 successfully to Neon and restarted on
localhost:8080; readiness returned `UP`. A live two-person approval remains an
operator acceptance step and needs two registered administrator identities.
See [Module 03](module-03-approvals.md) for coverage and API changes.

## Registration form validation

On 2026-09-22, registration gained browser-side bank-code and database-reference
format checks, visible examples, and specific invalid-field feedback. The
unapproved-reference message now explains that the bank database must be
configured first. All three existing UI tests passed and packaging succeeded.
Backend validation and the approved-database requirement remain unchanged.

## Temporary local MFA exception verification

On 2026-09-22, all **41 backend tests passed, with zero failures, errors or skips**
against the separate local PostgreSQL test instance. This includes the existing
MFA, disabled-identity, revocation and bank-isolation tests, two bank URL tests,
and three temporary-exception tests for default denial, exact-identity scoping,
deadline enforcement, loopback restrictions and maximum duration.

The tested application packaged successfully. Keycloak's original MFA client
binding was restored and read back successfully, then the user-authorized
temporary password-only flow was re-enabled. The initial administrator's
password-change requirement is preserved. The exception is stored locally and
does not automatically renew. See `docs/local-identity.md` for restoration.

The packaged application restarted successfully against Neon with the exception
loaded, and readiness returned `UP`. The launcher serializes the saved deadline
as an ISO-8601 UTC value, including when PowerShell parses JSON dates as objects.
Provider readback confirmed a required password authenticator and only
`UPDATE_PASSWORD` remaining for `cbs-admin`. The browser reached the password
change screen without an OTP prompt. Choosing the permanent password and
completing an authenticated portal session remain user steps.

## Local Keycloak integration

On 2026-09-21, the official Keycloak 26.7.4 archive was downloaded and its SHA-256
verified against the official release asset digest. The server started on
`127.0.0.1:8180`, and the `cbs-dev` realm was imported with mandatory password/OTP
authentication, S256 PKCE, exact redirect/CORS settings and real role/AMR mappers.

All **36 backend tests passed with zero failures, errors or skips**, including
three new transport-policy tests covering secure defaults, loopback-only HTTP
and refusal to bind CBS externally in local identity mode. Packaging initially
encountered a Windows file lock from the running application; after stopping that
process the same tested build packaged successfully.

The updated application started against Neon, readiness returned `UP`, and SQL
confirmed one active initial administrator matching the new provider subject.
The portal reports identity configured. In the browser, **Sign in** successfully
redirected to the Keycloak login form, with no observed browser console errors.
The provider's configuration and example token confirmed CBS audience/role
mapping; standard `basic` scope maps original authentication time. A synthetic
example token has no completed authentication and correctly contains no MFA
evidence. The browser has been left at sign-in for the user.

Permanent password selection, personal authenticator enrollment, and a completed
authenticated portal session require the user and have not been claimed as tested.
See `docs/local-identity.md` for first-login instructions and storage locations.

## Neon development database

On 2026-09-21, the user-supplied Neon `neondb` database was inspected and had no
application tables. The packaged application successfully applied Flyway V1, V2
and V3 using the direct Neon endpoint. All three history entries report success.
The `platform` schema now contains 11 tables:

`tenant`, `legal_entity`, `database_binding`, `registration_request`,
`tenant_membership`, `tenant_audit`, `staff_identity`, `access_session`,
`identity_audit`, `branch`, and `branch_membership`.

`public.flyway_schema_history` tracks these migrations. The application was
restarted on port 8080 using the pooled Neon endpoint and readiness returned
`UP`. A separate pooled client connection confirmed TLS 1.3. PostgreSQL's
`pg_stat_ssl` reports the proxy-to-backend connection, so client TLS was verified
with the client's connection information instead.

`scripts/run.ps1` now loads the ignored `.local/development-database.json` by
default. `-UseEnvironmentDatabase` selects explicitly supplied environment settings
instead. Existing local PostgreSQL data was preserved; this change initialized
the Neon schema and did not copy local records. Database integration tests remain
separate from this development database. No destructive test fixtures were run
against Neon.

## Module 02 final verification

Verified locally on 2026-09-21: Maven `verify` completed successfully with
`CBS_INTEGRATION_TESTS=true`: **33 tests, zero failures, zero errors, zero skipped**.
The frontend's three automated tests also passed. The final logout display
safeguard was followed by another frontend test run and successful JAR packaging.

- Foundation and signed-JWT checks continue to pass; Flyway applies V1 through V3.
- The 20 database-backed administration scenarios cover the previous tenant
  lifecycle checks plus staff registration, disabled identities, MFA requirements,
  session revocation, fresh-authentication cutoffs, idle/absolute session limits,
  branch permissions, cross-bank denial, and immutable identity audit history.
- Frontend checks cover markup escaping, lifecycle actions and error messages.
- The packaged application starts against local PostgreSQL, and readiness returns
  HTTP 200. The portal was opened and visually inspected in the in-app browser.
  Its setup checklist and navigation work; no browser warnings or errors were
  observed during these checks.

The application remains running at `http://127.0.0.1:8080/admin/index.html`, with
the workspace PostgreSQL instance on port 55433, as requested. Application logs
and the process ID are in ignored `.local/application*.log` and
`.local/application.pid`. These are local development processes, not installed
system services.

No deployed identity provider is configured. Browser verification therefore covers
the public setup experience; a real OIDC login and authenticated portal workflows
still require provider configuration and end-to-end verification. Authenticated
backend tests use generated RSA keys with the real JWT decoder and PostgreSQL.

## Module 01 final verification

Verified locally on 2026-09-21: Maven `verify` completed successfully with
`CBS_INTEGRATION_TESTS=true`: **25 tests, zero failures, zero errors, zero skipped**.
The executable JAR was packaged successfully.

- 7 foundation checks, including the V1-to-V2 control-plane migration and rerun.
- 6 signed-JWT checks: trusted signature, issuer, audience, expiry, required claims
  and rejection of a different signing key.
- 12 tenant administration scenarios, covering registration/replay/payload conflict,
  uniqueness and rollback, concurrent registration, concurrent version checks,
  authorization and validation, legal entities and audit history, the bank lifecycle,
  migration failure/retry, refusal of the control database, abandoned-attempt recovery,
  old-worker fencing, and rollback when audit persistence fails.

The bank lifecycle test uses real PostgreSQL databases with distinct roles. It
verifies cross-bank API denial, database-level connection denial, ownership checks
against aliased database targets, suspension/resume, membership role changes and
revocation. Identity tests use generated RSA keys and the real JWT decoder; no
production identity provider was configured or contacted.

Temporary Module 01 databases and roles were removed by the test suite. The
workspace database instance was stopped after verification. CI configuration is
provided, but GitHub-hosted CI has not been executed.

## Earlier Module 00 verification

Verified locally on 2026-09-21 using Java 21, Spring Boot 4.1.1, Maven 3.9.11,
and an isolated PostgreSQL 18.3 instance bound to localhost.

### Foundation automated checks

`verify` with `CBS_INTEGRATION_TESTS=true`: 7 tests passed, zero failures,
zero errors, zero skipped.

- Anonymous liveness response contains no infrastructure details.
- A caller-supplied tenant header cannot grant API access.
- Write requests and non-public actuator paths are denied.
- Valid correlation IDs survive rejection and logging context is cleared.
- Invalid correlation IDs are replaced with valid UUIDs.
- Flyway applies migration V1; re-running applies zero additional migrations.
- Readiness succeeds with a working PostgreSQL connection.

### Foundation packaged-service smoke check

Ran the executable JAR on localhost port 18080 against PostgreSQL port 55433.

| Scenario | Observed result |
| --- | --- |
| Database available | Readiness HTTP 200 |
| Banking route with untrusted tenant header | HTTP 401 |
| Database stopped after startup | Readiness HTTP 503 |
| Same database outage | Liveness HTTP 200 |

The test application and database were stopped after verification. Workspace-local
tools and test data remain ignored by Git. No system service was installed.

The initial packaged startup encountered a Windows JDK loopback socket error with
the environment's default temporary directory. An explicit workspace socket
directory resolved it; `scripts/run.ps1` supplies that setting.

## Module 05.1: Chart of Accounts (2026-09-24)

- Full backend suite: 58 tests passed, zero skipped; local CBS test cluster on
  port 55434. Another project's PostgreSQL on 55433 was left unchanged.
- UI suite: 8 tests passed. Browser fixtures verified account submission to the
  Approval Inbox, category/normal-balance defaults, search, immutable structure
  in the edit dialog, French labels, and invalid-code form validation.
- Packaged application started on 8080. Flyway applied control-store V6 to Neon;
  readiness returned UP and an unauthenticated chart API request returned 401.
- No chart accounts were created in a live bank. Live maker/checker acceptance
  remains pending. The existing temporary development MFA exception expired on
  September 23; this feature did not extend or alter authentication exceptions.

## Module 05.2: Ledger Accounts (2026-09-24)

- Full backend suite: 64 tests passed, zero skipped, using isolated local CBS
  PostgreSQL databases. Tests include bank database writes, route ownership,
  approval/staleness, dependency protection, V1-to-V2 bank upgrade, transactional
  failure and recovery after bank commit but control rollback.
- UI suite: 10 tests passed. Isolated browser fixtures verified preparation,
  account entry, submission into Approval Inbox, pending-to-ready retry states,
  and French labels; no browser errors were observed.
- Packaged application started successfully. Neon control migration V7 applied;
  readiness returned UP, the live portal shows Ledger Accounts, and an
  unauthenticated ledger endpoint request returned 401. Keycloak discovery works.
- Existing live bank databases are upgraded through the authenticated **Prepare
  ledger storage** action. No sample live ledgers were inserted. Live acceptance
  with two real staff identities remains pending.

## Not yet verified or implemented

GitHub-hosted CI execution, Docker Compose startup on this machine, connection to
a deployed identity provider/JWKS endpoint, production deployment/rollback, live customer
transaction journeys, load capacity, backup restoration and regulatory compliance.
These are not implied by the checks above.

## 2026-09-29 — Module 05.3 Journals and ledger balances

- Full backend suite: 68 tests, zero failures/errors/skips, on disposable local PostgreSQL databases at port 55434.
- UI suite: 12 passing tests, including exact integer-minor-unit arithmetic above the JavaScript safe integer range and escaped journal data.
- Integration evidence covers approval separation, large decimal posting, full linked reversal, immutable records, bank/entity isolation, invalid precision/date/balance, stale requests, pending-ledger guards, nonzero balance deactivation guards, partial-write rollback, control acknowledgement loss, concurrent duplicate retries and concurrent distinct postings.
- The first integration run found a shared-trigger NEW-record field mismatch; the trigger now branches before reading the table-specific field. The full suite passed after the fix.
- Isolated browser fixtures verified rejected unbalanced entries, balanced submission into Approval Inbox, retry to Posted, ledger balances, French labels and no browser warnings/errors. Fixture data never accesses Neon.
- Packaged application started on port 8080. Neon control migration V8 applied successfully; readiness is UP. Existing local Keycloak started on port 8180.
- Existing bank databases require the explicit Prepare journal storage action to apply bank V3. No live journal or reversal was submitted; two-person live acceptance remains outstanding.
- The previous local password-only MFA exception expired September 24. The user explicitly authorized a new eight-hour extension on September 29; it expires September 30 at 00:23:28 IST. CBS was restarted to load it.

## 2026-10-05 — Module 05.4 Accounting reports

- Full backend suite: 72 tests, zero failures/errors/skips; isolated local PostgreSQL only.
- UI suite: 13 passing tests, including report escaping and exact decimal display.
- Added tests cover business-date cutoffs, statement opening/closing balances, full reversals, pending exclusion, bank commit before control acknowledgement, bank/entity/currency isolation, authorization, CSV headers/formula escaping and turnover drift detection without automatic repair.
- Browser fixtures verified Trial Balance, Ledger Statement filters and summaries, reconciliation discrepancy warning, French labels and CSV download. The browser download-event wait timed out, but the actual downloaded CSV was located and parsed successfully (two fictional ledger rows).
- Packaged application is running on port 8080 with readiness UP. Identity provider runs on port 8180. Unauthenticated accounting-report requests return 401. No new migration is needed beyond bank V3/control V8.
- No financial data was added or changed in Neon. Live staff acceptance remains pending. The previous temporary password-only sign-in exception remains expired; this module did not renew it.

## 2026-10-05 — Module 05.5 Business Day Close

- Full backend suite: 75 tests, zero failures/errors/skips; disposable local
  PostgreSQL databases on port 55434. UI suite: 14 passing tests.
- New tests cover maker/checker separation, pending approvals and deliveries,
  weekend/holiday advancement, date-change restrictions, bank scope and access,
  rollback, bank commit before control acknowledgement, concurrent retry,
  database rejection of closed-date postings, and reconciliation failures with
  successful recovery. One earlier schema-version assertion was updated from
  bank V3 to V4; the complete suite then passed.
- Browser fixtures verified close readiness, saved entity/currency totals,
  submission into Approval Inbox, the maker's inability to self-approve, and
  French labels. Screenshot: `.local/business-day-close-preview.png`. All browser
  demonstration records are fictional and disconnected from Neon.
- Packaged application restarted on 8080; readiness returned UP and the new
  unauthenticated business-day endpoint returned 401. Neon control migration V9
  applied successfully. Existing banks require the authenticated **Prepare
  closing storage** action to install bank V4 before closing.
- No live bank date was advanced and no financial journal was inserted by this
  increment. Live acceptance with two staff identities remains pending. The
  earlier password-only sign-in exception is still expired and was not renewed.

## 2026-10-06 — Module 06.1 Deposit Product Factory

- Full backend suite: 79 tests passed, zero failures/errors/skips; UI suite: 17
  passed. Packaging succeeded. The first test attempt could not reach PostgreSQL
  because the stopped local test cluster started on its default port. Restarting
  that workspace cluster on dedicated port 55434 resolved the environment issue.
- Product tests cover separate approval, idempotency and concurrent retries,
  rollback of product/revision/decision, stale and rejected requests, immutable
  history, exact large decimals, precision/order validation, cross-bank mapping,
  disabled-currency and chart-dependency guards, endpoint permissions and paging.
- Browser fixtures verified creation/submission to Approval Inbox, pending state,
  maker self-review protection, saved revision details and French labels. No
  browser warnings/errors were observed. Preview: `.local/product-factory-preview.png`.
- Neon control migration V10 applied successfully. The packaged application runs
  on 8080 with readiness UP. The products endpoint returns 401 without a token.
  The existing Keycloak instance runs on 8180 and discovery returns 200.
- No live product/customer/financial data was created. Bank schema remains V4;
  Product Factory adds control-store configuration only. The expired MFA exception
  was not changed. Live two-person acceptance and production checks remain pending.
- Full delivery status and next steps are in `docs/application-progress.md`.

## 2026-10-06 — Module 07.1 Individual Customer Onboarding

- Full backend suite: 82 tests passed, zero failures/errors/skips, on disposable
  local PostgreSQL databases. UI suite: 20 tests passed. Packaging succeeded.
- Coverage includes profile validation, activation review references, normalized
  duplicate documents, possible-duplicate review, independent approval, concurrent
  submission/retry, transaction rollback, lost acknowledgement after bank commit,
  append-only bank events, rejected changes, cross-bank scope, permissions,
  directory field minimization, audited detail views and UI escaping/idempotency.
- Browser fixtures verified individual registration, submission to Approval Inbox,
  masked identity numbers in comparison values, maker self-approval protection,
  full detail rendering and French labels. All demonstration records are fictional.
- Neon control migration V11 applied successfully; the application runs on 8080,
  readiness is UP and anonymous customer API requests return 401. Existing bank
  databases require **Prepare customer storage** to apply bank V5. No live customer
  record was created and no bank balance or business date was changed.
- Live acceptance with two staff identities remains pending. The earlier expired
  password-only exception was not renewed. This module records manual review;
  automated identity/sanctions checks and production privacy controls remain future work.

## Module 08.1 — 6 October 2026

Full isolated PostgreSQL suite passed: 85 backend tests, zero failures/errors/skips. UI suite passed: 23 tests. Follow-up account checks verify deactivated-product opening refusal, chart dependency protection and expired-document eligibility. Browser fixture verifies account opening submission, approval comparison, pinned terms and lifecycle form without live bank data. See module-08-accounts.md for scope and limits. Control V12 and bank V6 migrations; existing bank upgrades use Prepare account storage.

Runtime verification: packaged application restarted successfully; readiness UP, anonymous account API 401. Neon control migration V12 applied. Bank V6 is available through authenticated Prepare account storage; no live account/customer was created. Browser screenshot: .local/deposit-accounts-preview.png (fictional isolated fixture).

## Module 08.2 — 6 October 2026

Full isolated PostgreSQL suite: 89 backend tests, zero failures/errors/skips; 26 UI tests passed. A follow-up integration test confirmed that direct journal writes cannot bypass customer subledger checks. Atomic funding, first-deposit minimum, exact large decimals, customer/GL reconciliation, status guards, stale requests, duplicate references, rollback, lost acknowledgement and concurrent retries verified. Browser fixture uses fictional receipts only. See module-08-funding.md for operation and scope. Migrations: control V13, bank V7.

Runtime: packaged application started successfully with Neon control V13 applied; readiness UP and anonymous funding API 401. Bank V7 is available through authenticated preparation; no live funding records were created. Screenshot .local/funding-balances-preview.png shows fictional browser fixtures.

## 7 October 2026 — account operations, transfers and statements

- Full Maven verify: 95 tests, zero failures/errors/skips on isolated PostgreSQL 55434.
- Final focused recheck after migration indexes/production guard refinements: 6 tests passed.
- Node UI suite: 30 passed.
- Verified hold/minimum enforcement, atomic transfer failure rollback, full funding reversal, lost acknowledgement/concurrent retry recovery, maker/checker and HTTP access controls, statement opening/closing/running totals and pagination.
- ProductionGuard rejects insecure identity/database configuration, MFA exceptions and bootstrap users; guard tests do not constitute production deployment certification.
- Fictional browser preview verified operation balance display, hold request submission and statement modal navigation.
- Packaged application started; Neon control migration V14 successful; readiness UP. Bank V8 is available through storage preparation.
- No production deployment, live financial test, bank disaster-recovery drill, hosted CI run or payment-provider certification performed.

### 7 October transaction limits and statement export

97 backend tests passed against isolated local PostgreSQL. A subsequent focused run of both account-operation scenarios passed after adding authenticated CSV export endpoint checks. Thirty UI tests passed. Final packaging succeeded after releasing the running JAR lock. The statement dialog and Export CSV control were inspected in a browser using fictional fixtures; screenshot: `.local/statement-export-preview.png`.

### 7 October branch operations

98 backend tests passed (`.local/branch-full-tests.log`) and 33 UI tests passed (`.local/branch-ui-tests.log`). New integration coverage exercises branch assignment, missing policy rejection, staff and branch roles, non-admin MFA, manager approval, immutable attribution, daily usage after hold release and revoked membership. The browser's isolated fictional branch fixture rendered and submitted the request form; screenshot: `.local/branch-workspace-preview.png`. Final package succeeded (`.local/branch-package.log`).

### Mandatory policy regression — 2026-10-07

- Full backend: 98 passed, 0 failures, 0 skipped (`.local/mandatory-limits-tests.log`).
- UI: 34 passed (`.local/mandatory-limits-ui-tests.log`).
- Follow-up integration: missing-policy API and absent/pending funding-policy enforcement passed (`.local/limit-coverage-tests.log`).
- Portal bundle and Maven package succeeded. Tests used isolated local PostgreSQL, not Neon.

### Teller cash verification — 2026-10-07

The complete backend suite passed 101 tests with no failures, errors or skipped tests (.local/cash-final-tests.log). The UI suite passed 38 tests (.local/cash-ui-tests.log). The cash integration scenario covers independent approval, permissions, mandatory limits, insufficient balances, mismatched counts, day-close count invalidation, atomic cash/customer/GL rollback, lost control acknowledgement, repeated delivery, immutable cash entries and a direct-journal bypass attempt. RWF, UGX and KES precision checks use exact decimal arithmetic.

The foundation migration test now allocates and removes a uniquely named test database, preventing an old draft migration in the reusable local database from contaminating later runs. No migration repair or reset was performed against Neon.

Browser verification used the fictional fixture server, not a live bank session: counted close and customer cash deposit forms, scoped account selection and successful approval submission were inspected. Screenshot: .local/customer-cash-preview.png. A full authenticated live teller workflow still requires the bank's storage preparation, roles, approved limits and normal MFA sign-in.

Final customer-cash HTTP integration passed (.local/cash-http-tests.log), including request binding through the authenticated branch endpoint. The portal bundle and application package also built successfully (.local/cash-package.log). The application now exposes cash storage preparation from the bank profile; existing bank databases are upgraded through the ownership-checked authenticated service, not by bypassing sign-in.

### 2026-10-07: cash exception reporting and verified recounts

- Full isolated PostgreSQL backend suite: 101 tests, zero failures/errors/skips (.local/cash-exception-tests.log).
- UI suite: 39 tests passed (.local/cash-exception-ui-tests.log).
- Cash integration scenario now covers authenticated discrepancy reporting/resolution, idempotent report and resolution replay, source and destination freezes, day-close blocker, wrong recount, self-review and teller-review rejection, unauthorized branch reads, unchanged customer/cash balances and immutable evidence tables.
- Browser: inspected the report form with fictional KES denomination counts using the isolated fixture server. Screenshot: .local/cash-exception-preview.png. No real bank financial transaction was created by the browser check.
- Package succeeded (.local/cash-exception-package.log); restarted workspace application, control migration V18 applied successfully and readiness returned UP. Current bank schema remains V9. Authenticated live-bank acceptance was not performed and the expired MFA exception was not renewed.
- The actual loss/gain adjustment and cash reversal workflows remain pending.

### 2026-10-07: approved cash shortage/overage adjustment increment

- Final full backend suite: 102 tests, zero failures/errors/skips (.local/cash-adjustment-final-tests.log). Tests use isolated local PostgreSQL.
- UI suite: 41 tests passed (.local/cash-adjustment-ui-tests.log).
- The first full run caught a ledger-name lookup against the wrong control table. Corrected it to use the approved ledger definition, then passed the focused cash scenario and the full suite.
- Cash integration now checks exact discrepancy amount, expense/income classification, missing limits, manager-only submission, requester and original-reporter approval exclusion, authenticated ledger selection/request submission, frozen pending cases, atomic rollback, lost acknowledgement recovery, repeated delivery, duplicate-case rejection, shortage and overage balances, daily count invalidation, and unchanged customer balance.
- A serialization compatibility test verifies ordinary cash requests omit the new null exceptionId field, preserving historical payload replay.
- Browser verification used the isolated fictional fixture (--branch --adjustment): frozen till, exact fixed KES 500 shortage, approved expense ledger selector and physical count/justification form. Screenshot: .local/cash-adjustment-preview.png. No real financial transaction was submitted.
- Existing banks require authenticated ownership-checked preparation to schema V10. This increment does not seed live financial limits or ledger mappings, renew MFA exceptions, or establish production acceptance.

- Release verification: package succeeded (.local/cash-adjustment-package.log); the restarted application applied control migration V19 and returned readiness UP. Actual per-bank V10 migrations remain an authenticated Prepare cash storage operation; live-bank acceptance was not performed.
