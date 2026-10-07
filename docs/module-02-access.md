# Module 02: identity, branch access and administration portal

The portal is served by the Spring Boot application at `/admin/index.html`.
It supports bank registration and lifecycle changes, staff registration and access
changes, legal entities, branches, bank/branch grants, session termination and audit
inspection. No bank or identity data is displayed before authentication.

## Build and run

Prerequisites: Java 21, Maven 3.9.11+, Node.js 24 and PostgreSQL.

```powershell
npm.cmd ci --prefix ui --ignore-scripts
npm.cmd test --prefix ui
./scripts/mvn.ps1 --batch-mode --no-transfer-progress verify
# Set CBS_DB_URL, CBS_DB_USER and CBS_DB_PASSWORD as documented in README.md.
./scripts/run.ps1
```

Maven builds the frontend automatically during `generate-resources`, using the
locked npm dependencies. The resulting executable JAR includes the portal; no
separate frontend service is required. Open `http://127.0.0.1:8080/admin/index.html`.
With no identity-provider configuration, this opens an informative setup screen;
bank and staff APIs remain closed. It is not a demo login or a bypass.

## Connect your identity provider

For the prepared development Keycloak instance, see [local identity setup](local-identity.md).
The Windows run script loads its saved configuration automatically. The HTTPS
requirements below apply to hosted providers; the explicit local development
exception accepts HTTP only for `127.0.0.1` or `localhost` and requires CBS itself
to bind to loopback.

CBS integrates with an existing OpenID Connect provider. Register a **public SPA
client** using authorization code with S256 PKCE, with an exact redirect URI such
as `http://127.0.0.1:8080/admin/index.html` for local development. Use HTTPS for
deployed portal addresses. Enable the required CORS origins for discovery and token
exchange. Do not give the browser a client secret. The provider's issuer and JWKS
endpoint must use HTTPS.

```powershell
$env:CBS_SECURITY_ENABLED = 'true'
$env:CBS_JWT_ISSUER = 'https://your-provider.example/issuer'
$env:CBS_JWT_JWKS_URI = 'https://your-provider.example/jwks'
$env:CBS_JWT_AUDIENCE = 'cbs-api'
$env:CBS_PORTAL_CLIENT_ID = 'cbs-administration'
$env:CBS_PORTAL_REDIRECT_URI = 'http://127.0.0.1:8080/admin/index.html'
$env:CBS_PORTAL_SCOPE = 'openid profile email'
$env:CBS_BOOTSTRAP_ADMIN_SUBJECT = 'the-initial-administrators-provider-subject'
```

If your provider requires an API scope to issue the CBS audience, include that
scope in `CBS_PORTAL_SCOPE`. Configure claim mapping on the provider. The **access
token**, not only the ID token, must contain:

| Claim | Required meaning |
| --- | --- |
| `iss`, `aud`, `sub`, `exp` | Exact configured issuer, CBS audience, stable staff subject, expiration |
| `iat` | Token issuance time, numeric UTC epoch seconds |
| `sid` | Provider session identifier, nonblank, maximum 200 characters |
| `auth_time` | Actual user authentication time, numeric UTC epoch seconds |
| `roles` | Include `PLATFORM_ADMIN` only for platform administrators |
| `amr` | For administrators, include `mfa` only after successful multifactor authentication |

Do not map token refresh time into `auth_time`. Do not set `mfa` unconditionally.
The provider owns passwords, MFA enrollment, factor recovery and its directory.
CBS staff registration links an existing provider subject; it does not create an
external account or send invitations. `roles` assignment also remains with the
provider. The backend verifies signatures, issuer, audience and token timestamps.

Bootstrap creates the initial CBS staff record only if it does not exist. It never
re-enables a disabled record or clears revocations. The configured subject still
needs a valid provider-issued platform-admin token with MFA. Remove the bootstrap
setting after setup if desired. New staff should then be registered through the
portal. Existing Module 01 identities are preserved by migration V3.

The portal uses [oidc-client-ts](https://authts.github.io/oidc-client-ts/classes/UserManager.html)
for PKCE, callback state handling and token processing. Tokens are held only in
memory. Temporary sign-in state and the PKCE verifier use session storage and are
consumed by the callback; tokens are never written to local or session storage.
Refreshing/closing the page clears the browser's tokens. No silent refresh or
offline access is requested. Sign-in requests a fresh authentication.

## Access and session policy

Every authenticated API request checks the current CBS staff record and session
in PostgreSQL. A disabled or unregistered identity is denied, including identities
whose JWT still carries the platform-admin role. Store failures deny access.
Bank and branch grants are also checked on each applicable request.

Defaults are configured under `cbs.access`:

| Setting | Default |
| --- | --- |
| `idle-timeout` | 15 minutes since the last successful API access check |
| `maximum-session-age` | 8 hours since provider authentication |
| `maximum-token-age` | 30 minutes since token issuance, or token expiry if sooner |
| `administrator-mfa-age` | 30 minutes since MFA-authenticated sign-in |

Durations must be positive and at most one day. Up to 60 seconds of clock skew is
allowed for future authentication/issuance timestamps. Idle time is server-side;
the portal does not keep a session alive with automatic background polling.
Provider session ID plus authentication time identifies a CBS session. A new real
authentication can therefore create a new CBS session even if the provider keeps
the same `sid`.

Disabling a staff identity revokes all its current sessions and advances its
revocation cutoff. Re-enabling it does not restore those sessions. Administrator
"Revoke all sessions" likewise requires new authentication after the cutoff;
refreshing an old login with a new token does not restore access. Because provider
timestamps use seconds, a fresh login may need to occur in the next second.

Ending a single session revokes it in CBS and clears local tokens. It does not
claim to terminate the provider's global SSO session. A fresh sign-in is requested
before access resumes. Revocations apply to subsequent API access checks; an
already-executing request is not forcibly cancelled. Changes made directly in the
provider, such as removing the admin role, take effect as new tokens arrive or old
tokens expire/age out; CBS does not introspect every JWT with the provider.

Session listings show up to 100 recent sessions; "Not revoked" does not override
idle or absolute expiry. Audit storage and session retention need a deployment
retention policy; no automatic audit deletion is implemented.

## Staff and session API

All paths are relative to `/api/v1`. Administrator routes require a registered,
active platform administrator with recent MFA. Writes take JSON and record actor,
reason and correlation ID atomically with the change.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/access/me` | Staff profile, current session ID and application role |
| GET | `/access/sessions` | Own recent sessions |
| DELETE | `/access/sessions/{id}` | End an owned session; another user's ID is not accepted |
| GET/POST | `/admin/staff` | List/register staff linked to the configured issuer |
| PATCH | `/admin/staff/{id}` | Update display name, email and enabled state |
| POST | `/admin/staff/{id}/revoke-sessions` | Revoke all sessions for a staff identity |
| GET | `/admin/identity-audit` | Paginated identity and session administration history |

Registration fields: `subject`, `displayName`, optional `email`, `reason`.
Update fields: `expectedVersion`, `displayName`, optional `email`, `active`, `reason`.
Revoke-all fields: `expectedVersion`, `reason`. A stale version returns 409. Duplicate
issuer/subject registration returns 409. An administrator cannot deactivate their
own record; another authorized administrator must do so. Register staff before
granting active bank or branch access.

## Branch roles and API

Branches belong to a bank and one of that bank's legal entities. A branch code is
unique within its bank. A valid identity must have an active bank membership and
an active membership in the particular branch. Roles never imply access to other
banks or branches.

| Branch role | Permissions |
| --- | --- |
| AUDITOR | READ_BRANCH |
| TELLER | READ_BRANCH, CASH_TRANSACT |
| BRANCH_MANAGER | READ_BRANCH, CASH_TRANSACT, APPROVE_BRANCH |

A BANK_AUDITOR bank membership limits effective permissions to READ_BRANCH, even
if a previous branch grant has an operational role. Suspended banks, disabled
branches, revoked memberships or disabled staff cannot obtain operational branch
access. Platform administrators do not automatically receive bank/branch access.
These permissions are guards for future banking modules; no cash posting or approval
transaction is implemented in Module 02.

| Method | Path | Purpose |
| --- | --- | --- |
| GET/POST | `/admin/tenants/{tenantId}/branches` | List/create branches |
| PATCH | `/admin/tenants/{tenantId}/branches/{id}` | Rename or enable/disable a branch |
| GET/PUT | `/admin/tenants/{tenantId}/branches/{id}/memberships` | Inspect/grant/revoke branch roles |
| GET | `/banks/{tenantId}/branches/{id}/access?permission=READ_BRANCH` | Check current effective branch access |

Creation fields: `legalEntityId`, `code`, `displayName`, `reason`.
Update fields: `expectedVersion`, `displayName`, `active`, `reason`.
Grant fields: `expectedVersion` (branch version), `staffId` (CBS UUID), `role`,
`active`, `reason`. Branch mutations also advance the parent tenant version and
appear in its audit trail. Refresh bank metadata after branch administration.

The access-check response is a snapshot, not a reusable authorization credential.
Future financial commands must invoke the same checks when executing. The full
maker-checker workflow remains Module 03.

## Portal limits and verification

Directories use server pagination and search the current page. Bank detail views
show up to 100 branches, memberships and audit entries, with that limit stated in
the UI. Larger histories can be read through the paginated APIs. All rendered values
are escaped; browser code does not embed untrusted HTML or persist bearer tokens.

The Java integration suite uses generated signed tokens and isolated PostgreSQL
databases to verify MFA, staff lifecycle, session ownership/revocation/expiry,
branch isolation and the existing tenant guarantees. UI tests cover escaping,
lifecycle action availability and actionable conflict messages. Connecting to an
actual provider and completing its MFA flow require the operator's configuration;
they are not implied by local test results.

## Module 03 approval changes

Staff profile and activation updates now require another administrator's approval. PATCH /admin/staff/{id} requires an Idempotency-Key UUID header and returns HTTP 202 with an approval request. Session revocation remains immediate. See [Module 03](module-03-approvals.md) for scope and API details.
