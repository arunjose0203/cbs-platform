# Daily processing at bank scale — 11 November 2026

This change follows the [W03 closure settlement](w03-closure-settlement.md) work. It replaces the single-transaction daily servicing batch, which was capped at 1,000 accounts, with an approved run that posts in resumable chunks. Bank acceptance remains pending.

## What changed

| Before | Now |
| --- | --- |
| One bank transaction for the whole run, capped at **1,000** funded accounts | No account cap. Runs post in chunks of `cbs.servicing.chunk-size` accounts (default **500**, allowed range 1–5000), one bank transaction per chunk |
| Any frozen account, ineligible customer, or account unable to afford the day's fee blocked the **whole** batch | That account alone is **excluded** with a recorded reason. Approvers see every exclusion before approving |
| Interval to the next working date capped at **31** days | Capped at **366** days (`MAX_PERIOD_DAYS`). Interest for the whole interval is calculated in one step, as before |
| Every account's calculation was put into the approval payload, the audit record and the bank receipt | Approval and audit show exact totals per currency, every exclusion, and the first 100 calculations. Items live in per-chunk records |
| Preparation read each account through several separate database connections | Positions, carries and account and customer records are read in bulk, a few queries per run |

## How a run works now

1. **Preview** (`POST …/servicing/preview`) returns:
   - the account count and chunk count
   - exact totals per currency (interest, withholding, fees, fee tax and net change)
   - every exclusion with its reason
   - the first 200 account calculations (`itemsTruncated` says whether there are more)
2. **Approval** by an independent administrator seals the business day, exactly as before. Approval stores:
   - the run header
   - one record per chunk
   - one journal delivery per account
   - one immutable exclusion record per excluded account
3. **Delivery** posts the chunks in order. For each chunk, in one bank transaction, the bank database:
   - inserts the run header (the first time only)
   - writes an immutable chunk receipt
   - registers the planned items
   - re-checks each account against its approved snapshot (definition, posted balance, holds, GL agreement and interest carry)
   - posts the journals and servicing events
4. **If a chunk fails**, it records its failure code and delivery stops. The run shows the code and stays incomplete. The business day stays **sealed**: no new postings, and day close is blocked. A retry (worker or **Retry posting**) resumes at the first unposted chunk. Posted chunks are never posted again: a lost acknowledgement is recovered from the bank receipt.
5. When every chunk has posted, the run completes and day close can proceed. A run with no accounts still seals the bank day.

The run page shows progress as "posted / total chunks" and the number of excluded accounts.

### What still stops the whole run

Problems with the accounting or configuration still stop the run, because they are not account-level conditions:
- a currency that isn't enabled
- an account change that hasn't been delivered yet
- bank and platform records that don't match
- an unreconciled customer subledger
- a missing servicing policy, or one that changes partway through the period
- a customer ledger that isn't ready
- pending approvals or deliveries

### Exclusion reasons

| Reason | Meaning |
| --- | --- |
| `ACCOUNT_FROZEN` (or another non-active state) | The account isn't active |
| `ACCOUNT_CUSTOMER_NOT_READY` | The customer isn't active, the review reference is missing, or the identity document has expired |
| `CUSTOMER_COMPLIANCE_RESTRICTED` | An open, expired or outdated compliance review |
| `INSUFFICIENT_AVAILABLE_BALANCE` | The day's fee and fee tax would take the balance below the product minimum after holds |

**Bank decision needed:** an excluded account gets **no interest, fees or taxes for that run's period**, and nothing is caught up automatically when it becomes eligible again. Its interest carry stays unchanged. Many banks keep accruing interest on frozen accounts. If the first bank requires that, or a catch-up on reactivation, it needs a separately specified change.

If a customer becomes ineligible **after** approval, the affected chunk stops with that code for review, rather than posting to a restricted customer. Earlier chunks stay posted, and the remaining chunks resume once the restriction is resolved.

## Database changes

- **Bank V24:**
  - adds immutable `bank.servicing_chunk` (receipts) and `bank.servicing_item` (planned items)
  - updates `bank.require_open_business_day()`, so a sealed day accepts only journals planned by its run (legacy inline runs are still recognised)
  - adds a trigger that rejects servicing events not planned by their chunk
- **Control V39:** adds `platform.servicing_chunk` (progress, attempts, failure) and immutable `platform.servicing_exclusion`.

Existing banks need **Prepare storage** (bank V24). Until then, a preview returns `SERVICING_STORAGE_NOT_READY`. Runs approved before the upgrade still post through the original single-transaction path. Upgrade when no run is awaiting posting.

## Verification

- `servicingPostsInResumableChunksAndExcludesOnlyIneligibleAccounts` uses a chunk size of 2 with 7 funded accounts. Five are serviced across 3 chunks; one frozen account and one that can't afford the fee are excluded. It checks:
  - exact per-currency totals
  - a bank failure injected into chunk 1, leaving chunk 0 posted, the day sealed, day close blocked and closure blocked
  - lost-acknowledgement recovery and repeated retries with no double posting
  - exact balances for every serviced and excluded account
  - receipt immutability and rejection of an unplanned journal on the sealed day
  - day close afterwards
- `servicingPostsInterestFeesTaxesAndStatementsExactlyOnce` (existing) still passes. An unaffordable fee now produces an exclusion instead of rejecting the preview.
- `servicingVolumeBeyondFormerCapReconcilesEveryAccount` is opt-in with `CBS_VOLUME_TESTS=true` and `CBS_VOLUME_ACCOUNTS`. Every account is opened and funded through the normal approval workflow. It then checks that every account's opening balance plus net change equals its posted balance, that every subledger equals its GL ledger, and that the total net movement equals the approved total. The result is in [application progress](application-progress.md).
- A UI test covers totals, exclusions and progress.

These are local results on fictional data. They are not a production load test: response times depend on hardware, network distance to the bank database, and the size of each account's history.
