# CBS verification

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

## Not yet verified or implemented

GitHub-hosted CI execution, Docker Compose startup on this machine, connection to
a deployed identity provider/JWKS endpoint, production deployment/rollback, financial
posting, load capacity, backup restoration and regulatory compliance.
These are not implied by the checks above.
