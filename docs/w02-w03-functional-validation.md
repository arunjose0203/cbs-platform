# W02 validation and W03 deposit closure increment

Date: 9 October 2026. Base commit: `fe23ba3`; implementation changes follow that baseline.

## Status

W02 has an automated validation pass and a reproduced posting defect fixed. Independent bank acceptance remains pending. W03 now supports a bounded zero-balance account closure workflow; full final settlement remains partial. This report does not certify production readiness.

## W02: administration, permissions, approvals and accounting

The targeted run exercised registration/provisioning and recovery, legal entities and lifecycle, authenticated administration, MFA/session checks, staff/branch permissions, independent approvals, stale requests, settings/currencies/calendars, chart and ledger definitions, journals/reversals, reconciliation, reports and business-day close. It also included production guards, runtime database restrictions and JWT/identity URL validation.

**D-W02-01 — disabled approval participants could still post a queued manual journal.** An integration test disabled the original maker after approval and before delivery. The ledger changed by 25 when zero was expected. The pre-fix test failed, reproducing the defect.

The shared journal store now requires both original approval participants to remain active before a first posting. It locks the staff records in deterministic order within the control transaction. Account-change delivery applies the same check. A matching bank-side receipt is checked first: a posting or closure already committed can still be acknowledged after a participant is disabled. This preserves recovery without creating a duplicate financial event.

Regression: `manualJournalDeliveryRechecksActiveParticipantsBeforeFirstPosting` checks disabled maker, disabled checker, successful posting after restoration, lost control acknowledgement and repeated retry after both users are disabled. The check concerns local staff activation; it does not claim to inspect current external identity-provider roles or extend bank-scoped roles to all remaining administration functions.

## W03: account closure

Use **Deposit accounts → Closure check**. The page displays decimal-string customer, held and general-ledger balances without converting them to floating-point numbers. It lists blockers or offers submission for independent approval with a mandatory reason and idempotency key.

Closure requires:

- An active bank, open business day, applied account record and matching bank-side definition.
- No unfinished customer operation, contract delivery or FX trade affecting the account.
- Zero customer and general-ledger balances, matching ledger binding and no holds.
- No linked contract history, external payment history or applicable servicing policy. These are conservative restrictions until their final-settlement/recovery workflows are available.
- No residual interest carry or unfinished bank servicing run.
- Current approval participants at first delivery. Closed accounts cannot be reopened through ordinary account management.

These checks run again during approval and first delivery. Existing tenant-version checks prevent stale approval. The bank transaction changes the account and writes its immutable receipt together; closure makes no financial posting because eligible balances are already zero. Funding history, customer entries, account-ledger binding, original records and audit history remain.

Bank migration **V22** rejects closing an account with nonzero balances, holds, linked contracts or interest carry, rejects changes to closed account payloads, and blocks new journal lines on closed customer accounts. Journal posting locks the bank account against concurrent closure. Existing databases must be upgraded through **Prepare account storage** with migration credentials available to the authorized migration path; restricted production runtimes should use the packaged migration procedure. Prepare applies the complete current bank migration set.

The API is `GET /api/v1/admin/tenants/{bankId}/accounts/{id}/closure`. Closing uses the existing approved account-change endpoint, not a direct state-changing GET.

Regression: `zeroBalanceClosurePreservesHistoryAndRechecksApprovalBeforeDelivery` covers unauthenticated/unauthorized reads, bank routing, positive balance and hold blockers, pending operations, database rejection of unsafe closure, complete funding reversal to zero, independent approval, idempotency, disabled maker, lost acknowledgement, duplicate retry, retained records, rejected subsequent funding and rejected reopening. Existing contract, payment and servicing integration scenarios additionally assert their closure blockers.

## Verification evidence

| Run | Result |
| --- | --- |
| W02 targeted backend validation | 69 passed; `.local/w02-validation.log` |
| Initial W03 deposit lifecycle validation | 10 passed; `.local/w03-validation.log` |
| UI automated tests | 98 passed; `.local/w02-w03-ui.log` |
| Operations unit tests | 21 passed; `.local/w02-w03-ops-tests.log` |
| Full backend regression | 160 executed: 155 passed; five database-free startup tests errored because the new journal dependency required a database. Dependency wiring corrected; final rerun below covers all five. `.local/w02-w03-full-tests.log` |
| Final affected-tests/package run | 19 passed, zero failures/errors/skips, including all five database-free startup cases, final storage/binding checks and closed-account SQL rejection. Executable JAR built. `.local/w02-w03-final-package.log` |

Packaged V22 recovery drill: **PASS**. Packaged migrations ran twice; control/bank schemas and configuration restored; fictional 100 KES customer balance reconciled; two pending deliveries survived, including a bank-posted transaction awaiting acknowledgement. Restricted runtime privileges were verified. Small-fixture restore took 11.583 seconds; this is not a production RTO. Evidence: `.local/w02-w03-recovery-drill.log`.

These runs overlap; their counts must not be added as unique coverage. Logs are ignored local artifacts. Retain CI artifacts and independent bank evidence for a release. All financial integration work used fictional data in isolated local PostgreSQL databases; live Neon banking data was not used.

## Remaining acceptance and development

1. Independently execute W02 acceptance cases with actual approved bank roles, limits, chart mappings, MFA identities and accounting expectations. Remaining broad administrator-only workflows need an agreed delegation policy. Blocked approved jobs also need an agreed cancellation/re-approval procedure when original staff cannot legitimately be reactivated; this increment does not replace their identities or bypass the block.
2. Add a separately approved closure settlement operation for positive balances and minimum-balance products. Ordinary withdrawals continue to enforce agreed minimum balances; no bypass was introduced.
3. Implement final interest, fee, tax and rounding-carry settlement, and decide the final accrual cutoff with the bank.
4. Define how settled contracts, written-off debt, late provider returns and payment exceptions are handled before allowing linked-account closure. Historical contracts/payments currently block closure even if apparently terminal.
5. Confirm required customer types, joint/corporate mandates, dormancy, deceased-customer processing and legal restrictions for the first bank. They remain scope gaps, not implied features of this increment.

The [Functional Gap & Acceptance Matrix](functional-gap-acceptance-matrix-2026-10-09.md) remains the governing backlog. A14 is now partially implemented beyond the original baseline; bank UAT and sign-off remain NOT RUN / PENDING.
