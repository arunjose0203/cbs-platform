# Month-end/year-end close and signer capture — 11 November 2026

This change follows [organizations, joint and minors' accounts](organizations-joint-and-minor-accounts.md). It covers matrix row F10 (period closing) and finishes the signer capture left open in F14/F16. Bank acceptance remains pending.

## Signer capture at the teller and for M-Pesa payouts

- **Cash withdrawals** (Branch workspace → Teller & cash): after the withdrawal details, a second step asks which authorised signers approved the instruction. It appears only when the account needs signers: a mandate exists, or the holder is an organization or a minor.
- **M-Pesa payout requests** (Payments) have the same step. The payout order now carries `signatories`, and the existing debit path checks them against the account mandate.
- **Signer list for branch staff:** `GET /api/v1/banks/{bankId}/branches/{branchId}/operations/accounts/{accountId}/mandate` lets branch staff with read access see an account's signers. It only works for accounts assigned to their branch.

## Month-end and year-end close

**1. Policy.** Business Day Close → **Close policy**, then submit for approval (`PERIOD_CLOSE_POLICY`). The policy sets:
- the fiscal year-end month
- one **retained earnings** ledger per legal entity and currency that books income or expenses. It must be an active, credit-normal equity posting ledger, and not a customer ledger

Once a bank has a policy, **every month must be closed on its last business day before that day closes** (`PERIOD_CLOSE_REQUIRED`). Banks without a policy are unaffected.

**2. Close the period.** On the last business day of a month, do this after daily processing, FX valuation, cash counts and every pending delivery are finished: **Close the period** → review → submit for approval (`PERIOD_CLOSE`).

The preview shows a trial balance for each legal entity and currency: debits, credits, income, expense and net result. The close is refused if any entity/currency doesn't balance.
- **Month end:** the approved trial balance is recorded. No journals are posted.
- **Fiscal year end:** for each entity and currency, one **closing journal** reverses every income and expense ledger balance and puts the net result in retained earnings (profit as a credit, loss as a debit). Afterwards, income and expense ledgers start the new year at zero.

**3. Seal.** Approval seals the business day:
- The control plane refuses new work (`PERIOD_CLOSE_FINALIZED`).
- The bank database rejects any journal on that date other than the approved closing journals ("Period is closed").

When the close is posted, each swept ledger is re-checked: it must still hold exactly the approved balance, or the close stops (`PERIOD_CLOSE_BALANCE_CHANGED`). The close posts in one bank transaction with an immutable receipt. A retry after a lost acknowledgement doesn't post twice. **Business Day Close** then proceeds as normal.

**Notes:**
- FX profit and loss ledgers can be swept by the closing journal. Other protected ledgers are balance-sheet ledgers, so the closing journal never touches them.
- An inactive income or expense ledger that still holds a balance blocks year-end close (`PERIOD_CLOSE_LEDGER_INACTIVE`).
- **There is no reopening.** Closed days and periods are never reopened. Corrections are posted in the current open period, with a reversal or adjusting journal that cites the original. If the bank needs a controlled prior-period reopening procedure, it has to be specified separately.
- The month boundary follows the calendar month. Period close uses the bank's own calendar (weekends and holidays), so "last business day" respects it.

## Database

- **Control V42:** `PERIOD_CLOSE_POLICY` and `PERIOD_CLOSE` approval actions, `platform.period_close_policy` and `platform.period_close`.
- **Bank V25:** immutable `bank.period_close` and `bank.period_close_journal`, the sealed-day rule in `bank.require_open_business_day()`, and the FX profit/loss exemption for closing journals.

Existing banks need **Prepare storage**.

## Verification

`monthAndYearEndClosesSealTheDayAndMoveProfitToRetainedEarnings` covers:
- the policy required, a wrong ledger type rejected, self-approval denied, and no mid-month close
- November: day close blocked until the month close, a balanced trial balance with exact income/expense/result, no journals, and postings refused once approved
- December (year end): a closing journal that debits income 350, credits expenses 130 and credits retained earnings 220
- an injected bank failure, lost-acknowledgement recovery and repeated retries
- income and expense at zero and retained earnings at the result afterwards
- a late journal on the sealed day rejected, and the receipt immutable
- the day closing into 2027

The full suite also passes: UI tests for the period close section, the shared signer step, and the earlier mandate and alert work. All data was fictional, with test business dates set directly for the scenario.
