# Module 01: bank tenant administration

Module 01 implements a backend administration API. Identity is supplied by an
external JWT issuer; bank membership is maintained in the control-plane database.
The Module 02 [portal and identity guide](module-02-access.md) now adds sign-in,
staff registration, branch permissions, MFA and session policies. Its required
session claims apply to all protected APIs. Banking transactions and full
maker-checker remain later modules.

## Enable authenticated access

The application remains closed to administration by default. Configure:

```powershell
$env:CBS_SECURITY_ENABLED = 'true'
$env:CBS_JWT_ISSUER = 'https://your-identity-provider.example/issuer'
$env:CBS_JWT_JWKS_URI = 'https://your-identity-provider.example/jwks'
$env:CBS_JWT_AUDIENCE = 'cbs-api'
```

Both URLs must use HTTPS. The issuer must match the token exactly. The provider
must issue RS256 access tokens with `iss`, `aud`, `sub`, `exp`, and, for platform
administrators, `roles: ["PLATFORM_ADMIN"]`. Token signature, issuer, audience,
expiry and nonblank subject are validated. No local password, test token issuer,
or shared-secret backdoor is enabled. Private test keys are generated at test time.

The provider owns administrator onboarding, MFA and assignment of the privileged
role. Grant this role only to trusted platform operators. An administrator can
manage memberships but does not automatically receive bank operational access.
Memberships are keyed by tenant + verified issuer + subject. A role or tenant header
supplied by a caller cannot establish bank membership.

JWT integration follows the [Spring Security resource-server model](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Approve database targets

Create an empty database and a distinct login role for each bank using your database
provisioning process. The API performs schema provisioning and verification inside
these operator-created databases; it does not create PostgreSQL servers or roles.

- Revoke PUBLIC CONNECT on each bank database and grant only its bank role.
- Do not give bank roles membership in another bank's role or cluster superuser rights.
- The provisioning role needs schema/table creation rights within its own database.
- Keep the control-plane database separate. The adapter refuses any database with
  a `platform` schema and refuses a bank database claimed by a different tenant.
- Use managed secret injection for usernames/passwords; never send them through the API.

Load [the configuration template](../config/tenant-databases.example.yml) with:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:./config/tenant-databases.example.yml'
$env:CBS_BANK_ALPHA_DB_URL = 'jdbc:postgresql://localhost:5433/bank_alpha'
$env:CBS_BANK_ALPHA_DB_USER = 'bank_alpha'
# Supply CBS_BANK_ALPHA_DB_PASSWORD through your local or deployment secret setup.
# Supply the corresponding BANK_BETA variables, or remove that entry from your private copy.
```

Supported URLs contain an explicit host, port and database name, with optional
`?sslmode=disable|require|verify-ca|verify-full`. Credentials or arbitrary connection
parameters in URLs are rejected. Use `verify-full` and configured trust material
for remote deployments. IPv6 literals and arbitrary driver options are not exposed
in this initial adapter. Approved connection references cannot be reassigned by an
API call; operators must keep their mappings stable.

## API catalogue

All paths are relative to `/api/v1`. Supply `Authorization: Bearer <access token>`.
JSON request bodies use `Content-Type: application/json`.

| Method | Path | Required access / behavior |
| --- | --- | --- |
| POST | `/admin/tenants` | Platform admin; register tenant and first legal entity |
| GET | `/admin/tenants` | Platform admin; paginated tenant list |
| GET | `/admin/tenants/{id}` | Platform admin; detail, version and provisioning outcome |
| PATCH | `/admin/tenants/{id}` | Platform admin; update display name and language |
| POST | `/admin/tenants/{id}/legal-entities` | Platform admin; add a legal entity |
| POST | `/admin/tenants/{id}/provision` | Platform admin; provision or recover a failed/abandoned attempt |
| POST | `/admin/tenants/{id}/suspend` | Platform admin; ACTIVE -> SUSPENDED |
| POST | `/admin/tenants/{id}/resume` | Platform admin; SUSPENDED -> ACTIVE |
| PUT | `/admin/tenants/{id}/memberships` | Platform admin; grant, change or revoke membership |
| GET | `/admin/tenants/{id}/memberships` | Platform admin; paginated membership list |
| GET | `/admin/tenants/{id}/audit` | Platform admin; paginated audit history |
| GET | `/banks/{id}` | Active bank membership; read metadata even while bank is suspended |
| GET | `/banks/{id}/operational-context` | BANK_OPERATOR membership, ACTIVE tenant, verified database identity |

List endpoints accept `limit` (1–100, default 25) and `offset` (0–1000000, default 0).
They return `{ "items": [...], "limit": 25, "offset": 0 }` in stable creation/ID
order (memberships use issuer/subject order). No delete endpoint is provided.
The operational-context response is a current access check, not a reusable
authorization token. Future financial commands must recheck membership and status
at execution and enforce the bank transaction boundary.

## Register and provision a bank

POST `/admin/tenants`, with a new UUID in `Idempotency-Key`:

```json
{
  "bankCode": "ALPHA_BANK",
  "displayName": "Alpha Community Bank",
  "defaultLanguage": "en-IN",
  "databaseReference": "bank-alpha",
  "legalEntity": {
    "legalName": "Alpha Community Bank Limited",
    "country": "IN",
    "functionalCurrency": "INR",
    "businessTimezone": "Asia/Kolkata"
  }
}
```

Returns HTTP 201 with `status: "REQUESTED"`, `version: 0`, immutable `id`, legal
entities and the logical database binding. A replay by the same issuer/subject
with the same key and payload returns HTTP 200, `Idempotency-Replayed: true`, and
the current tenant representation. A changed payload returns 409
`IDEMPOTENCY_CONFLICT`. Keys are retained; a failed registration transaction does
not consume its key. Bank codes and assigned database references are unique.

POST `/admin/tenants/{id}/provision`:

```json
{ "expectedVersion": 0, "reason": "Initial bank setup" }
```

Provisioning is synchronous. It durably records PROVISIONING and an attempt ID,
claims the bank database identity, applies bank Flyway migrations and verifies the
result before setting ACTIVE. Successful provisioning normally increments the
version twice: one for the claim and one for completion.

An external provisioning failure returns HTTP 200 with the durable tenant outcome
`status: "PROVISIONING_FAILED"` and `databaseBinding.failureCode`. Clients must
inspect the status, not infer activation from HTTP 200. Failures before an attempt
is claimed use the usual error response. No raw driver exception or credential
is returned. Migration history is stored separately in each bank database.

## Memberships and lifecycle changes

PUT `/admin/tenants/{id}/memberships`:

```json
{
  "expectedVersion": 2,
  "subject": "identity-provider-subject-id",
  "role": "BANK_OPERATOR",
  "active": true,
  "reason": "Assign bank operations responsibility"
}
```

Use `BANK_AUDITOR` for metadata inspection without operational context access.
Set `active` to false to revoke access. Revocation is checked in the database on
each request, including requests with an already-issued valid token. The membership
issuer is derived from the administrator's verified token, not the request body.

Suspend and resume requests contain the current `expectedVersion` and a nonblank
`reason`. Suspended banks remain visible to authorized staff for investigation;
operational context is denied. Resume does not rewrite bank migration history.

PATCH requests contain `expectedVersion`, `displayName`, `defaultLanguage` and
`reason`. Adding a legal entity uses `expectedVersion`, `legalEntity` (same shape
as registration) and `reason`. Country, currency and timezone are validated;
language tags are syntactically validated. This stores localization preferences;
it does not yet supply translated screens or documents.

Every mutation except registration requires the latest version. Conflicting
updates return 409 `STALE_VERSION`. On a lost mutation response, GET the resource
and inspect its version/audit before deciding whether another change is needed.
Do not blindly replace the expected version and replay a state-changing request.

## Audit and recovery

Registration, configuration changes, legal entities, membership changes, state
transitions and provisioning attempts record authenticated actor, correlation ID,
version, time, reason and relevant change details. Control-plane mutations and
audit writes commit together; audit failure rolls back the mutation. Audit rows
reject UPDATE/DELETE. Database owners can bypass database controls, so production
runtime roles must not own schemas or possess DDL/TRUNCATE privileges; use separate
migration credentials and external audit retention as part of operational hardening.

If a process crashes while provisioning, the tenant remains PROVISIONING with a
10-minute lease. After expiry, an administrator can retry `/provision` with its
current version and a recovery reason. A new attempt ID fences older workers;
their completion cannot overwrite the new result. A PostgreSQL session advisory
lock prevents simultaneous provisioning of the same physical database. Identity
reservation survives a failed migration so retrying cannot give the bank database
to a different tenant.

If provisioning fails, inspect `failureCode`, correct the operator-managed target
or migration issue, and retry using the latest version. Do not delete ownership
markers, modify applied migrations or automatically run Flyway clean/repair.

## Error contract

Errors use `application/problem+json`, stable `code`, and a correlation ID header.
Application errors also include `correlationId` in the body. Common codes:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_LEGAL_ENTITY / INVALID_LANGUAGE | Invalid fields |
| 401 | AUTHENTICATION_REQUIRED | Missing or invalid bearer token |
| 403 | ACCESS_DENIED / BANK_ACCESS_DENIED / BANK_OPERATION_DENIED | Required role/membership absent |
| 404 | TENANT_NOT_FOUND | Administrative lookup found no tenant |
| 409 | BANK_CODE_IN_USE / DATABASE_REFERENCE_IN_USE | Unique registration conflict |
| 409 | IDEMPOTENCY_CONFLICT / STALE_VERSION | Retry/update conflict |
| 409 | INVALID_TRANSITION / TENANT_NOT_ACTIVE | Lifecycle constraint |
| 409 | PROVISIONING_IN_PROGRESS / PROVISIONING_SUPERSEDED | Provisioning concurrency constraint |
| 422 | DATABASE_REFERENCE_NOT_APPROVED | No approved connection reference |
| 503 | CONTROL_PLANE_UNAVAILABLE / DATABASE_UNAVAILABLE | Store unavailable |

Other safe database failure codes include DATABASE_CONFIGURATION_INVALID,
DATABASE_BUSY, DATABASE_OWNERSHIP_MISMATCH, CONTROL_PLANE_TARGET_FORBIDDEN,
DATABASE_NOT_READY and DATABASE_PROVISIONING_FAILED.

## Verification

Run `verify` with `CBS_INTEGRATION_TESTS=true` and the normal `CBS_DB_*` variables.
The integration suite requires a local/disposable PostgreSQL administrator able
to create databases and roles. It creates uniquely named test control/bank databases
with separate roles and removes those fixtures on completion. Never point this
suite at a production cluster. Tests use generated RSA signatures, not bypassed
authentication. No real identity-provider account or bank data is required.
