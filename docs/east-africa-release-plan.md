# Rwanda, Uganda and Kenya release work

Confirmed target markets: Rwanda, Uganda and Kenya. These are product targets, not a claim of regulatory approval. Keep bank configurations isolated; do not reuse one country's calendar, tax rules, reporting rules or payment credentials for another.

## Implementation sequence and acceptance gates

1. **Bank/branch permissions and transaction limits:** connect existing branch memberships to financial submission and approval; capture branch and actor in immutable instructions; enforce per-operation and daily currency limits under a database lock; reserve limits for approved but unposted requests; prevent administrator routes bypassing limits. Test cross-bank denial, revoked roles, independent checker permissions and concurrent limit exhaustion.
2. **Teller and cash:** vault/till ownership, opening cash, dual-control allocation, deposits, withdrawals, denomination counts and closing differences. Every movement must post balanced accounting exactly once. Block closing with pending movements or unexplained differences.
3. **Interest, fees and daily processing:** versioned calculation policies, day-count and rounding rules, effective dates, accruals, capitalization and fees. Restartable daily jobs need unique bank/date/job receipts, reconciliation and approval before closing. Product and country rules require bank confirmation.
4. **Statements and notifications:** full-range bounded CSV export implemented as a backend increment; portal download and export integration coverage remain. Add PDF, delivery preferences, recipient verification, transactional outbox, retries and provider receipts. Never send account data to unverified destinations.
5. **Payments and settlement:** provider-neutral payment state machine, idempotency, signed callbacks, timeout reconciliation, settlement files, suspense accounts and dual-control exception resolution. Select actual providers and obtain sandbox contracts for each market before claiming integrations work.
6. **FX, deposits and lending:** approved rate books, currency rounding and realized FX accounting; term-deposit schedules and maturities; loan schedules, disbursement, repayment allocation, arrears and impairment policy. Test accounting invariants and schedule boundaries before exposing posting actions.
7. **Production acceptance:** restore drills, load and endurance tests, independent security review, MFA, operational monitoring, credential rotation, disaster recovery and bank acceptance. Record measured results against agreed targets.

## Current boundaries

Existing financial APIs remain platform-administrator-only. Existing branch role checks do not yet provide an end-to-end teller workflow. Statement export uses the existing authorization and tenant/account ownership checks, a single repeatable-read snapshot, a 10,000-entry ceiling and an export audit event. It rejects oversized ranges instead of silently truncating them.

Payment providers, hosting region, peak transaction targets, recovery objectives and country-specific regulatory acceptance requirements are still open. Shared development can proceed while these are selected. No production-ready percentage is supported by the current evidence.
