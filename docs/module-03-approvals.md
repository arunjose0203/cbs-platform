# Module 03: Approval Inbox

Bank suspension/resumption and staff profile or enabled/disabled changes now
create pending requests. They do not change live records until a different
platform administrator approves. Both users need active CBS identities and valid
administrator sign-ins under the same configured identity provider. Bank-wide
administration remains a platform-administrator permission; bank-only users cannot
list or decide these requests.

## Portal workflow

1. Submit a bank suspension/resumption or staff update from its existing page.
2. Open **Approval Inbox**. Filter by request type or bank. The bank selector shows
   the first 100 banks; the API accepts any authorized bank UUID.
3. **My requests** includes your pending and closed requests. You can cancel your
   pending requests but cannot approve or reject them.
4. Another administrator opens the request, compares the submitted snapshot with
   the proposed values, and approves or rejects with a reason.
5. **Approval history** contains approved, rejected, cancelled and stale requests.
   The detail page records each event, actor, reason and time.

Two distinct administrator identities are required. The application does not
create a privileged checker automatically or bypass MFA for a second identity.
Configure the checker's identity-provider role and register its stable subject in
**Staff & access** before testing with two people.

## API changes

All paths below are relative to `/api/v1`. Existing request bodies are unchanged.

| Method and path | Result |
| --- | --- |
| `POST /admin/tenants/{id}/suspend` | HTTP 202 with pending approval request |
| `POST /admin/tenants/{id}/resume` | HTTP 202 with pending approval request |
| `PATCH /admin/staff/{id}` | HTTP 202 with pending approval request |
| `GET /admin/approvals` | Paginated requests; `scope=INBOX\|MINE\|HISTORY`, optional `action` and `bankId` |
| `GET /admin/approvals/{id}` | Request snapshot and event history |
| `POST /admin/approvals/{id}/approve` | Apply the change and record approval atomically |
| `POST /admin/approvals/{id}/reject` | Close without applying the change |
| `POST /admin/approvals/{id}/cancel` | Maker-only withdrawal |

The three submission endpoints require a UUID `Idempotency-Key` header. Reusing
the same key and payload returns the original request, even after a decision.
Different payloads with the same key return `IDEMPOTENCY_CONFLICT`. Decisions use
`{"reason":"Reason for this decision"}`. Duplicate matching decisions by the
same reviewer return the stored result without applying the change again.

## Integrity and audit

Flyway V4 adds `platform.approval_request` and append-only
`platform.approval_event` in the control database. Request IDs link the resulting
bank/staff audit entry to the approval evidence. No bank database migration is
needed. Requests contain business values, not credentials or tokens.

Decision processing locks the request and target. A changed target version closes
the request as `STALE` without updating the live record. The maker must still be
active. Application changes and approval events share one transaction; a failed
business rule rolls both back. Concurrent decisions cannot apply a change twice.
Issuer filters and platform-role checks apply to reads and writes.

## Initial scope and Module 04 extension

Module 04 adds `BANK_SETTINGS` requests for bank currencies, language defaults and
business calendars. They use the same two-person decision, audit and version checks.
See [Module 04](module-04-reference-data.md) for payloads and API details.

This increment covers bank suspension/resumption and staff profile/activation
updates. Bank registration, provisioning, branch configuration, bank/branch role
assignments and new staff registration retain their existing audited behavior.
Emergency session revocation stays immediate. Extending approvals to those
operations, configurable approval policies, multiple approval levels, delegation,
expiry and notifications are future increments. No financial posting is added.

## Verification

The integration suite uses separate local PostgreSQL databases and signed test
identities. It covers submission idempotency, mandatory approval through the
existing HTTP endpoints, self-approval denial, issuer/role isolation, stale
requests, cancellation/rejection, concurrent decisions, immutable events, disabled
makers and rollback after failed business checks.

`node ui/test/approval-preview.mjs` serves isolated in-memory UI fixtures on
localhost:18082 for browser checks. It cannot access CBS or the database and is
not packaged into the application. Actual sign-in and database behavior are
verified separately; fixture screenshots are not evidence of live approvals.
