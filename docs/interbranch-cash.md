# Inter-branch cash and cash in transit

Implemented 7 October 2026. This increment supports physical vault shipments within one bank, legal entity and currency. It does not execute a payment-provider transfer or currency conversion.

## Setup

1. Deploy control migration V21. Prepare each participating bank through the authenticated, ownership-checked **Prepare cash storage** action to bank schema V12.
2. Register approved vaults at two active branches in the same legal entity and currency.
3. Create an approved debit-normal asset ledger for cash in transit. On its first dispatch it must have no previous or queued journals, customer binding or cash-location binding. It can then serve multiple shipments within its entity and currency. Direct journals and ordinary external cash movements cannot use it.
4. Assign independent branch managers at each end. Bank, branch and initiating staff limits for `CASH_DISPATCH` and `CASH_RECEIVE` apply. Transit movements share these existing limit budgets with external vault movements. No live limits are supplied by the application.

## Workflow

In **Branch workspace → Teller & cash custody**, use **Send to another branch** on a vault. Choose the destination vault, transit ledger, amount, expected receipt date, shipment reference and supporting evidence. An independent source-branch manager approves the request. After posting, the source vault is reduced and cash in transit is increased; the destination vault is unchanged.

The destination's **Inter-branch cash in transit** table shows the shipment. Choose **Receive shipment**, enter the amount physically received and its denomination count, and record receipt evidence. An independent destination-branch manager approves it. Posting increases destination cash and reduces cash in transit atomically.

Partial receipts are supported. Posted receipts and approved receipts awaiting posting both reduce the amount available for further receipt requests. Every approval rechecks the outstanding amount and current bank version. The bank database independently prevents total receipts and returns from exceeding dispatch.

The source branch can use **Receive returned cash** for cash physically returned from an uncompleted shipment. This also needs counts, evidence and an independent source manager's approval. It reduces the shipment's outstanding amount. A return is a new linked cash receipt, not deletion of the dispatch.

Pending requests can be cancelled by their maker through the existing review workflow. Posted transit movements are excluded from generic cash reversals. Cash already received by the destination must be sent back as a new shipment; it cannot be claimed as an outstanding return on the original.

## Accounting

| Event | Debit | Credit |
| --- | --- | --- |
| Dispatch | Dedicated transit asset | Source vault cash |
| Destination receipt | Destination vault cash | Dedicated transit asset |
| Physical return | Original source vault cash | Dedicated transit asset |

Journal lines, cash entries, immutable shipment/receipt records and cash balances commit in one bank transaction. A failed posting rolls back all effects. A retry after a lost control acknowledgement finds the original receipt and does not post again. Transit ledgers are protected against manual posting at both service and database levels.

## Discrepancies and daily processing

The table reports sent, received, returned and outstanding amounts, posting status, expected date, partial settlement and overdue status. Overdue uses the later of the bank business date and today's date in the bank's configured timezone, so a blocked business day does not hide a late shipment.

Record only the cash actually received. A short receipt leaves the remainder in transit; an excess receipt is rejected. Receipt evidence can describe investigation findings. There is no automatic write-off, income recognition or bank-policy assumption. Existing physical vault discrepancies continue through the separate cash-exception workflow.

Day close is blocked while any shipment remains outstanding. All normal pending-approval, pending-delivery, physical-count and open-exception gates also apply. This release does not support an overnight carry policy. Resolve the shipment through verified receipts/returns before day close.

## API

Base: `/api/v1/banks/{bankId}/branches/{branchId}/operations/cash`.

- `GET /transit`: branch-scoped shipment status, outstanding first, at most 100 rows.
- `GET /transit-destinations`: at most 100 active other-branch vaults; manager permission required. UI filters entity and currency; server validation is authoritative.
- `POST` existing cash route with `kind=DISPATCH` and `transit={destinationId,expectedDate,returned:false}`.
- `POST` existing cash route with `kind=RECEIVE`, receipt counts and `transit={dispatchId,returned:false}` for destination receipts, or `returned:true` for physical source returns.
- Existing request detail, approve, reject and cancel routes apply. All submissions require an idempotency key, bank version, current business date, reference and reason.

## Remaining scope

Carrier/insurance integration, seal custody events, shipment-specific investigation cases and write-off approvals, attachments, receipt printing, denomination inventory, cross-entity/FX shipments, full history search/pagination, configurable overnight carry and bank acceptance remain pending. Denomination counts are evidence; they do not maintain a note inventory. This increment does not establish production readiness.
