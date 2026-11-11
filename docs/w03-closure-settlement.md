# W03 final closure settlement — 11 November 2026

Base commit: `7486d03`. This increment completes the W03 resume point and adds positive-balance account closure. Bank acceptance remains pending.

## W03 resume result

The interrupted 13 October W03 retry was rerun on a fresh, isolated local PostgreSQL 16 instance (loopback only, generated credentials, no Neon data). Before any code change:

| Run | Result |
| --- | --- |
| Full backend `verify` (all W02 + W03 + other integration tests) | **160 passed**, zero failures/errors/skips; JAR packaged |
| UI suite | **98 passed** |

The earlier W03 errors were caused by the database environment (Windows shared-memory exit), not by application logic. On a Linux host the JVM's `Asia/Calcutta` zone ID is rejected by some PostgreSQL builds; run integration tests with `TZ=UTC` / `-Duser.timezone=UTC` in that case.

## What the closure flow now supports

Closing a funded account with money in it is now two independently approved steps:

1. **Final settlement** (`CLOSURE_SETTLEMENT` account operation). This pays out the account's **whole posted balance**, including the product minimum balance, to an approved asset ledger in the same currency and legal entity. Any interest carry below the smallest currency unit is recorded as forfeited.
2. **Closure**, using the existing approved account-change request. Its eligibility rules are unchanged, except for the servicing rules described below.

Use **Deposit accounts → Closure check**. When the only blockers are the remaining balance or the residual interest carry, the page offers **Final settlement before closure**: choose the paying asset ledger, enter a reference and reason, and submit for approval. After the settlement posts, the same page offers the closure request.

### Rules enforced

- A mandatory bank-wide transaction limit policy for `CLOSURE_SETTLEMENT` is required. A missing policy denies the payout.
- The settlement is administrator-only, including for branch-assigned accounts, in line with closure itself. The maker cannot approve their own request.
- The amount must equal the posted balance exactly, and there must be no holds. Release holds first. The bank database checks this again at posting time, so a balance that changed after approval is rejected rather than partly paid.
- Every other closure blocker must be clear before settlement: an inactive bank, a closed business day, pending operations, an unreconciled GL position, contract history, payment history, a running servicing batch, or unprepared storage.
- **Interest cutoff:** interest is settled through the last completed daily servicing run, which is normally the business day before closure. There is no pro-rata accrual for the closing day. Fees and tax are already posted by each daily run, so nothing else is outstanding.
- **Residual carry:** the sub-unit fractional interest carry is recorded in `bank.closure_settlement.forfeited_carry`. The closure guard allows a nonzero carry only when it equals the recorded forfeited amount.
- **After settlement, only closure may follow.** New funding, holds, transfers or a second settlement are rejected with `ACCOUNT_PENDING_CLOSURE`. The bank database rejects any further journal line on the account's ledger. Daily servicing skips settled accounts, so a daily fee cannot drive a zero balance negative.
- Ordinary withdrawals still protect the product minimum. Only the final settlement can take it.
- An applicable servicing policy **no longer blocks closure by itself**. A running servicing batch still does (`ACCOUNT_CLOSURE_SERVICING_REQUIRED`).

### Database changes

- **Bank V23** adds the `CLOSURE_SETTLEMENT` operation kind and an immutable `bank.closure_settlement` record (one per account). It also extends the operation integrity trigger (balance must be exactly zero afterwards, and the record must match), the closure guard (carry waiver) and the posting guard (pending closure). Existing banks need **Prepare account storage**. Until then the closure check shows `ACCOUNT_STORAGE_NOT_READY`.
- **Control V38** allows a `CLOSURE_SETTLEMENT` transaction limit. The transaction-limits page lists it as a missing policy for enabled currencies.

### API

`POST /api/v1/admin/tenants/{bankId}/account-operations` with `kind: "CLOSURE_SETTLEMENT"`, `assetLedgerId`, `amount` equal to the posted balance, current `businessDate`, `reference`, `reason` and an `Idempotency-Key`. `GET …/accounts/{id}/closure` now also returns `carry` (the residual carry) and `settled`.

## Verification

- `positiveBalanceClosureSettlesWholeBalanceForfeitsCarryAndThenCloses` covers:
  - funding plus a real servicing run and day close that leaves a residual carry
  - the missing-limit denial
  - minimum-balance protection for ordinary withdrawals
  - field, amount and hold rejections
  - self-approval denial
  - pending-request blocking
  - direct-SQL tampering rejection
  - rollback and lost-acknowledgement retry
  - exact zero balance and GL movement
  - the immutable forfeited-carry record
  - the pending-closure posting guard
  - rejected funding, holds and second settlement
  - exclusion from the next servicing preview
  - final approved closure
- `servicingPostsInterestFeesTaxesAndStatementsExactlyOnce` now asserts the new rule that a policy alone does not block closure.
- Two UI tests cover the settlement form: exact balance, retry-key stability and translated labels. They also check that a settled account offers no second settlement.
- See the verification table in [application progress](application-progress.md) for full-suite counts.

All data was fictional, in isolated local databases.

## Still out of scope (bank decision required)

- Pro-rata interest for the closing day, and early-closure penalties on savings/current products.
- Closing accounts that have contract history (term deposits, loans) or payment history. These remain blocked until settled-contract and late-return handling is defined.
- Reversing a final settlement. Once settled, the account can only be closed. A bank-approved correction procedure is needed if a payout must be undone.
- Paying out to an external rail or teller till as part of settlement. Settlement credits an approved asset ledger, and the physical or external payout is evidenced separately.
- Dormancy, deceased-customer, joint and corporate mandate processing.
