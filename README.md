# CBS platform

Java 21 / Spring Boot 4.1.1 / PostgreSQL. This repository contains the platform
foundation, Module 01 bank tenant administration and Module 02 identity/branch
access, including an administration portal.

## Current scope

- Executable Spring Boot service; APIs stay closed until JWT authentication is configured.
- Minimal liveness and database-aware readiness endpoints.
- Validated correlation IDs on responses and in logging context.
- Flyway control-plane and per-bank schema migrations.
- Tenant registration, legal entities, memberships, suspend/resume and audit history.
- Idempotent registration, optimistic version checks, and recoverable provisioning.
- Verified JWT identities and database-backed bank membership authorization.
- Staff lifecycle, administrator MFA enforcement, branch permissions and session revocation.
- A responsive administration portal using OpenID Connect authorization code with PKCE.
- Local PostgreSQL Compose definition and CI verification workflow.
- HTTP/security checks and optional real-PostgreSQL integration checks.

Ledger, customer accounts, currency processing, translations and the full
maker-checker workflow are planned. See the [Module 02 setup guide](docs/module-02-access.md)
for portal sign-in and identity configuration, and the [Module 01 API guide](docs/module-01-api.md)
for tenant/database administration.

## Prerequisites

- Java 21 and Maven 3.9.11 or later.
- Node.js 24 and npm for the portal build.
- Docker Compose for the supplied local database, or a running PostgreSQL instance.

Workspace-local Java and Maven, if present under `.tools`, are used by
`scripts/mvn.ps1`. These tools and the local dependency cache are ignored by Git.
On other machines, install the prerequisites before using the script, or use
`mvn` directly. Spring Boot requirements:
https://docs.spring.io/spring-boot/system-requirements.html

## Build and test without a database

```powershell
npm.cmd ci --prefix ui --ignore-scripts
npm.cmd test --prefix ui
./scripts/mvn.ps1 --batch-mode --no-transfer-progress verify
```

Or `mvn --batch-mode --no-transfer-progress verify` with system tools. Database
integration tests are explicitly skipped unless `CBS_INTEGRATION_TESTS=true`.

## Run locally using Neon

Local development sign-in is also configured with Keycloak. Start
`./scripts/run-local-identity.ps1` in a separate terminal before starting CBS.
See [first login and restart instructions](docs/local-identity.md). Initial
credentials are in the ignored `.local/FIRST-LOGIN.txt` file.

This workspace now uses Neon for the application's development database. Run:

```powershell
./scripts/run.ps1
```

The script loads `.local/development-database.json`, which is ignored by Git.
It contains the pooled JDBC URL, direct migration URL, database username and
password. Credentials are separate from the JDBC URLs so startup logs do not
include the password. The local application still opens at
`http://127.0.0.1:8080/admin/index.html`; its database records are stored in Neon.
No local PostgreSQL process or Docker service is needed for this startup mode.

For another workstation, copy `config/development-database.example.json` to
`.local/development-database.json` and fill it in locally. Both URLs must point
to the same database. The supplied configuration requires TLS and channel binding.
Flyway applies pending migrations automatically at startup and validates existing
migration history. The current tables are in the `platform` schema of `neondb`;
`public.flyway_schema_history` records migrations. Separate bank business databases
still need their own approved bindings when banking modules are introduced.

This config is loaded only by `scripts/run.ps1`, not by Maven or tests. Keep
database integration tests pointed at a separate disposable local test database.

## Alternative: run using a local PostgreSQL database

Run these commands from the repository root in the same PowerShell session:

```powershell
# Keep this password for the lifetime of the local database volume.
$env:CBS_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:CBS_DB_URL = 'jdbc:postgresql://localhost:5433/cbs_control'
$env:CBS_DB_USER = 'cbs_control'
docker compose up -d --wait
./scripts/mvn.ps1 --batch-mode --no-transfer-progress verify
./scripts/run.ps1 -UseEnvironmentDatabase
```

The application binds to `127.0.0.1:8080` by default. To restart an existing
database volume, reuse its original password; changing the environment variable
does not change a database user's stored password. Store local settings outside
Git. `.env.example` shows variable names; Java does not automatically load `.env`.
`-UseEnvironmentDatabase` bypasses the saved Neon configuration. If you previously
set `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER` or `SPRING_FLYWAY_PASSWORD` yourself,
clear them or set them to this same local database before using this alternative.

The Windows run script sets a workspace-local JDK socket directory. This avoids
the `Unable to establish loopback connection` error encountered with this host's
default temporary directory. It does not change system settings.

Check from another terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

Both return only an overall status. Readiness includes control-plane database connectivity;
liveness does not. Startup fails if database connection or migration fails.
The public portal opens at `http://127.0.0.1:8080/admin/index.html`.
By default, only its static assets, sign-in configuration and health probes are
public. Administration APIs remain closed until identity is configured.
Enable authenticated access using the Module 02 configuration guide.
No generated login or development backdoor is provided.

## Run database integration checks

With the local database running and the same database environment variables set:

```powershell
$env:CBS_INTEGRATION_TESTS = 'true'
./scripts/mvn.ps1 --batch-mode --no-transfer-progress verify
```

Use a dedicated development/test PostgreSQL cluster. Tests apply real migrations.
Module 01 tests require a PostgreSQL administrator able to create databases and
roles; they create and remove uniquely named fixtures with separate bank credentials.
CI runs against its own disposable PostgreSQL service.

## Packaging and operations

`verify` produces `target/cbs-platform-0.1.0-SNAPSHOT.jar`. Run it with Java 21
and the database variables above. `CBS_PORT` and `CBS_BIND_ADDRESS` override the
local bind defaults. Health endpoints reveal no database or configuration details.

`docker compose stop` stops the local database without deleting its volume.
The local Compose database user is a development bootstrap user. Production must
separate migration and runtime privileges and supply managed secrets, TLS, backup,
restore, identity, and deployment controls before any bank pilot.

Do not edit applied migration files. Add a new versioned migration. Application
rollback requires compatibility with the current schema; no automatic destructive
schema rollback is provided.

## Module documentation

See [architecture](docs/architecture.md), [Module 01 API and operations](docs/module-01-api.md),
and [Module 02 identity and portal setup](docs/module-02-access.md).
Next: Module 03 full maker-checker approval workflows and operational exceptions.

The full sequence is in [development roadmap](docs/development-roadmap.md).
Local verification evidence is in [verification](docs/verification.md).
