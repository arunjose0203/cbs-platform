# Cash and customer transaction reversals

Implemented 7 October 2026. Reversals post a new, full, opposite journal on the current business date. Original financial records remain immutable. This increment does not establish production acceptance or execute an external payment recall.

## Supported workflows

| Original posting | Reversal effect |
| --- | --- |
| External vault receipt | Reduce vault cash; restore the original settlement asset |
| External vault dispatch | Restore vault cash; reduce the original settlement asset |
| Vault/till transfer | Move the original amount back between the same locations |
| Customer cash deposit | Reduce till cash and customer liability/balance |
| Customer cash withdrawal | Restore till cash and customer liability/balance |
| Internal customer transfer | Debit the original recipient and credit the original sender |
| Recorded non-cash withdrawal | Restore the customer balance against the original paying asset |
| Non-cash funding | Existing full funding reversal remains available |

Cash and customer entries commit with their journal and immutable receipts in one bank transaction. One original journal can have only one reversal, enforced in both control and bank storage. Cash receipts additionally have a unique original receipt reference. Recovery from a failed bank transaction leaves balances unchanged; recovery after a lost control acknowledgement reuses the committed receipt.

## Access and prerequisites

- Prepare bank storage through the existing authenticated cash/account storage action. Current control schema is V20; bank schema is V11. Ownership is checked before per-bank migration. No previously applied migration is edited.
- Approve real bank, branch and requesting staff limits for CASH_REVERSAL, WITHDRAWAL_REVERSAL and TRANSFER_REVERSAL as applicable. Cash deposit reversal additionally uses FUNDING_REVERSAL limits; cash withdrawal reversal additionally uses WITHDRAWAL_REVERSAL limits. Unassigned administrative customer accounts use bank limits and platform administration approval. No production values are seeded.
- Assigned branch roles, independent approval, current bank version, current business date, active bank/account/customer eligibility, applied ledger definitions and currency/entity ownership remain mandatory.
- Any affected till must have an open session for the current business date and the requesting custodian. External vault receipt/dispatch reversals require a branch manager. Unresolved cash exceptions block reversals involving their locations.
- Deposit/transfer reversals protect holds and the contractual minimum balance on the account being debited. Cash outflows require sufficient cash at the debited location. Withdrawal reversals credit the customer and do not consume their withdrawable balance.
- Approval reserves the reversal against the original journal. Approved requests awaiting posting count toward daily limits. Cash daily counts are invalidated by posted reversals and must be completed again before day close.

## Screens and API

- Branch workspace → Teller & cash custody → Reverse posted cash: select an original transaction, provide a unique reversal reference and evidence, and submit for independent approval. Amount, account and location identities are derived from the original and validated by the server. Cash approval details include signed source/destination cash effects and the customer operation type.
- Branch workspace → Reverse customer transaction: select a posted non-cash withdrawal or internal transfer. Transfer account direction is automatically swapped.
- Account operations → Reverse transaction offers the same customer workflow; cash-managed rows use the dedicated cash workspace.
- GET /api/v1/banks/{bankId}/branches/{branchId}/operations/cash/reversible lists up to 100 eligible posted cash transactions. POST to the existing cash base path with kind REVERSAL and originalId submits the request.
- GET /api/v1/banks/{bankId}/branches/{branchId}/operations/reversible lists up to 100 eligible branch customer postings. The existing branch request endpoint accepts WITHDRAWAL_REVERSAL and TRANSFER_REVERSAL.
- Existing administrative account-operation endpoints also accept those customer reversal kinds with originalId. Ordinary account and journal routes cannot reverse cash-managed transactions independently.

Selectors exclude already-reserved reversals. A stale choice is also rejected by the server. Request idempotency and immutable receipt comparison preserve retry safety. Null originalId on older cash requests remains omitted from JSON so historical payloads remain compatible.

## Deliberate limits and remaining work

Reversals are full amount only. A reversal cannot itself be reversed. Cash shortage/overage adjustments, count records and till lifecycle events are excluded; adjustment reversal needs a separate case-reopening policy. Closed/frozen/ineligible accounts are not bypassed. No original account activation is rewound when an opening deposit is reversed.

These workflows record independently verified corrections. They do not initiate external provider recalls, return physical cash, or determine whether a real-world transaction should be reversed. Original receipt and return/correction evidence must be checked by bank staff.

Still pending: configurable reversal-age/authorization policies, historical selector pagination/search beyond 100 items, adjustment case reopening, physical receipt printing, full translations, bank acceptance, inter-branch cash/in-transit, denomination inventory and emergency custody reassignment. These remaining teller controls precede or accompany the subsequent interest/fees/daily-processing work.
