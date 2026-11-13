# Bank duties and access reviews — 13 November 2026

Round 9 of the [go-live plan](go-live-plan-2026-11-13.md). It covers matrix rows F03 (bank/branch roles and authority) and F04 (approval scope). Bank acceptance remains pending.

## The problem it solves

Before this round, most approval actions accepted any **platform administrator** as maker or checker, in every bank. Lending, payment-operator, branch and cash work already had bank-scoped grants. Configuration, accounting, customer, compliance, treasury and access changes did not. One platform administrator could post journals, clear compliance alerts and grant lending authority in any bank, as long as a second administrator approved.

## How it works

A bank can **enforce duties**. Once it does, a platform administrator must also hold an approved, unexpired **duty grant for that bank** to:

- **submit** a request for that bank (the submit duty),
- **approve or reject** one (the approve duty, held separately),
- **read** one, or see it in the Approval Inbox (any duty for that request, or the audit flag).

The maker can always cancel their own request.

| Duty | Approval actions |
| --- | --- |
| Access administration | Bank duties, enforcement switch, lending permissions, payment-operator permissions |
| Bank configuration | Bank settings, transaction limits, branch assignment and limits, denominations, cash location registration, deposit products, servicing policy, credit policy |
| Finance | Chart of accounts, ledgers, journals and reversals, day close, daily servicing runs, period-close policy and close, report definitions |
| Customer operations | Customers, organizations and mandates, deposit accounts, funding, account operations on unassigned accounts and closure settlement, alert preferences, term-deposit contracts and servicing |
| Payments | M-Pesa payout orders, reconciliation, statement actions and payment cases taken by an administrator |
| Treasury | FX rates, rate withdrawals, books, closing rates, valuations, trades and reversals |
| Compliance | Compliance case reviews, screening lists, monitoring policies, alert dismissals |

**Unchanged:**
- Platform-wide actions are not bank duties: bank suspension and resumption, and staff profile changes.
- Loan, credit and collections work keeps using [lending grants](bank-scoped-lending-access.md).
- Branch staff keep using branch memberships.
- Payment operators keep using payment roles.

Duties govern the platform-administrator path into each bank. The classification is an exhaustive Java `switch` in `BankDuties.duty`, so a new approval action cannot be added without assigning it a duty.

**Banks that do not enforce duties behave exactly as before.** Turning enforcement on narrows authority; it never widens it.

## Setting it up

Staff & access → **Bank duties & access review**, then choose the bank.

1. **Propose duties** for each person. Each duty is set to none, submit, approve, or both. Add **audit** for read-only reviewers. Leave the expiry empty for a standing duty, or set one for time-limited (break-glass) cover; it ends by itself. Another administrator approves each grant, and nobody can approve their own.
2. Give **at least two people** access administration with both submit and approve. Enforcement is refused until they exist (`BANK_DUTY_LOCKOUT`).
3. **Enforce duties.** This is a request too (`BANK_DUTY_POLICY`), approved by someone else.

While duties are enforced:
- A grant change that would leave fewer than two access administrators is refused.
- When a request is approved, the system checks that the maker *still* holds the submit duty. A duty that expired or was revoked while the request was waiting gives `BANK_DUTY_MAKER_REVOKED`.
- Any grant or policy change increases the bank's version, so pending requests for the bank become stale. This is the same behaviour as lending grants.

### Lockout recovery

If enforcement is on but fewer than two access administrators remain (for example, one left the bank and their identity was disabled), the bank cannot fix this with grants. In that state only, any platform administrator may submit a request to **switch enforcement off**. A different administrator must still approve it. The request stays visible in every administrator's inbox. Re-grant access administration, then enforce again.

## Access review

The same page shows **who can do what in this bank**: every person with bank duties, lending roles, payment roles or branch roles, with their last sign-in. Findings per person:

| Finding | Meaning |
| --- | --- |
| `STAFF_DISABLED_WITH_GRANT` | Identity disabled but grants are still recorded |
| `DORMANT` | Not signed in for 90 days, or never |
| `DUTY_EXPIRED` / `TIME_LIMITED_GRANT` | Break-glass duty that has ended, or is still running |
| `CONFLICT_ACCESS_AND_TRANSACTIONS` | Access administration combined with finance, customer operations, payments or treasury duties, or with a non-auditor lending, payment or branch role |
| `CONFLICT_CUSTOMER_AND_COMPLIANCE` | Customer operations and compliance held together |

Bank-level findings are `DUTIES_NOT_ENFORCED` and `FEWER_THAN_TWO_ACCESS_ADMINISTRATORS`. Conflicts are flagged rather than blocked, because small banks may need to accept some of them. The reviewer should record that decision in the review statement.

**Sign access review** records an immutable attestation bound to a SHA-256 hash of exactly what the reviewer saw. Sign-in times are shown but are not part of the hash. If access changes between reading and signing, the signature is refused (`ACCESS_REVIEW_STALE`). When duties are enforced, only staff with the audit flag can sign. Access administrators can read the report but cannot sign their own review.

## API

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/admin/tenants/{bank}/bank-duties` | Report, findings, snapshot hash, history, signed reviews |
| `POST /api/v1/admin/tenants/{bank}/bank-duties` | Propose a grant or revocation (`BANK_DUTY`), with `Idempotency-Key` |
| `POST /api/v1/admin/tenants/{bank}/bank-duties/policy` | Propose enforcing or not (`BANK_DUTY_POLICY`), with `Idempotency-Key` |
| `POST /api/v1/admin/tenants/{bank}/bank-duties/reviews` | Sign an access review: `{snapshotHash, statement}` |

Grant body: `{expectedVersion, staffId, active, submit:[…], approve:[…], audit, expiresAt|null, reason}`.

## Database

**Control V46** adds:
- the `BANK_DUTY` and `BANK_DUTY_POLICY` approval actions
- `platform.bank_duty`, `platform.bank_duty_policy`, immutable `platform.bank_duty_event` and immutable `platform.access_review`

Deferred constraint triggers refuse any grant, policy or history row that does not match an approved request with the same payload. A grant must also have been approved by someone other than the grantee. The bank schema is unchanged (V26).

## Not covered yet (Round 9b)

- **Direct, non-approval routes.** About 140 administrator endpoints (reads, previews, draft uploads) still check platform administration only. Every change to money, customers, limits or policy goes through the approval engine and is covered above. Reads of bank data outside the Approval Inbox are not yet limited by duty.
- **Recheck at delivery.** Lending grants are checked again when a queued posting is delivered. Duties are checked at submission and approval only.
- **Amount bands per duty**, such as journals over a set amount needing a senior approver. Lending grants already have per-operation amount limits.
- **Scheduled review reminders and alerts** on break-glass grants. These come with monitoring (plan item P-05).

## Verification

- `bankDutiesSegregatePlatformAdministratorsWhenEnforced` covers:
  - grant validation, self-approval refusal, and enforcement through HTTP
  - submit/approve separation, inbox and detail visibility, and platform actions staying unaffected
  - the two-administrator guard, maker recheck after a time-limited duty expires, and cancellation by the maker
  - access-review conflicts, attestation by audit only, the stale-snapshot refusal, and database immutability
  - lockout recovery
- `BankDutiesTest` covers the classification, the SQL mapping, the expiry format, findings, hash stability, and the fixed key order that keeps the hash reproducible across restarts.
- Full backend `verify`: 195 passed, 1 opt-in volume test skipped. UI: 119 passed.
- 7 UI tests cover the portal screen.
