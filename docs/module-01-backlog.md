# Module 01: tenant and bank administration

Status: implemented as a backend API. See [API and operations](module-01-api.md).
External JWT identity and approved operator-created bank databases are required
to enable administration and provisioning outside the integration tests.

## Scope

Register bank tenants and legal entities; define provisioning lifecycle and
trusted routing. Customer accounts and financial posting are out of scope.

## Records

- Tenant: immutable ID, unique bank code, display name, status, timestamps,
  optimistic concurrency version.
- Legal entity: immutable ID, tenant ID, legal name, country, functional
  currency, business timezone.
- Database binding: tenant ID, approved logical connection identifier and
  provisioning status. Credentials belong in a secret manager.
- Audit: actor, tenant, operation, correlation ID, time and safe change details.

## Lifecycle

Requested -> Provisioning -> Active -> Suspended -> Active

Provisioning -> Provisioning Failed -> Provisioning (retry)

Failed provisioning remains visible and can be retried idempotently. Do not mark
a tenant Active until its database migrations and connectivity checks succeed.
Suspension prevents new business commands; authorized investigation remains
possible. Do not implement tenant deletion as a cascading database delete.

## Work items

1. Implemented control-plane migrations and tenant repository.
2. Integrated verified JWT platform-administrator identity.
3. Implemented audited registration with idempotency and uniqueness checks.
4. Added bank schema provisioning, ownership checks and migration history against approved existing databases.
5. Added operational context resolution after database-backed membership checks.
6. Added audited suspension/resume, version checks and stale-worker fencing.
7. Added administrative API, legal-entity and membership management, and operational documentation.

## Acceptance scenarios

- Duplicate bank code is rejected deterministically.
- Retrying registration cannot create a second tenant.
- A bank-A principal cannot access bank B by changing headers or URLs.
- A failed migration cannot activate a tenant.
- Suspension rejects new banking commands.
- Registration and status changes identify their authenticated actor in audit.
- Logs never contain credentials or connection-string secrets.
