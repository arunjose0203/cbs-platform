# Denomination inventory and cash receipts

Implemented 7 October 2026. Control schema V22; bank schema V13 (current bundle also includes servicing V23/V14).

## Enable a bank

1. Open the bank profile and prepare its cash/ledger storage. Ownership checks still apply to the dedicated bank database.
2. Use Cash denominations to propose the complete catalogue for each enabled currency. Enter explicit face values and NOTE/COIN classification; an independent administrator approves it.
3. Settle all outstanding shipments involving a location. Close open tills. In Branch workspace → Teller & cash custody, a branch manager requests Activate inventory with a physical denomination count equal to the book balance. Another manager approves.
4. Activate both ends of a transfer. Once a catalogue exists, day close requires every cash location in that currency to be activated.
5. Enter the notes/coins moved on each receipt, dispatch, transfer, deposit or withdrawal. Opening/closing and daily counts must match both total cash and individual quantities.

The catalogue is additive, up to 50 unique positive face values per currency. A face value has one classification; separate coin and note stock at the same face value is not supported. Each location holds at most 1,000,000,000 units per face value. Existing aggregate balances remain usable until inventory activation; historical denomination movements are not invented. Reversals of transactions lacking original denomination evidence are blocked after activation.

Bank cash-note entries and opening inventory markers are immutable. Movements, customer balances and journals commit together. Negative denomination stock, mismatched counts and shipment denomination over-receipts are rejected by database checks. Shortage/overage adjustments use their approved physical counts; reversals restore the original quantities when stock permits.

## Receipts

Use Receipt / reprint beside a posted monetary cash delivery. The branch-scoped endpoint verifies the bank receipt, allocates an audited copy-generation event and returns a stable bank receipt number, transaction ID, date, currency, amount, location, account/reference and recorded count evidence. Print / Save PDF invokes the browser print dialog. Copy generation is idempotent with the same key; further generations are marked REPRINT. Generation does not prove that a printer produced paper.

Receipt numbers are unique within the bank and may contain gaps. Nonmonetary records also consume the bank identity sequence. Bank/receipt identity, not a globally unique receipt number alone, identifies a receipt. Printer hardware, silent printing, PDF delivery, signatures, jurisdiction-specific receipt templates and a historical receipt search remain outside this increment. Printing currently starts from the recent posted delivery list.

## API

- GET/POST /api/v1/admin/tenants/{bank}/cash-denominations: catalogue and independent approval request.
- POST /api/v1/banks/{bank}/branches/{branch}/operations/cash with kind INVENTORY: approved activation.
- POST /api/v1/banks/{bank}/branches/{branch}/operations/cash/{request}/receipt with Idempotency-Key: generate printable evidence.

No live catalogue, opening count, financial limit or cash transaction was seeded by this implementation. Carrier custody, denomination exchange/reclassification, shipment write-offs and emergency custody reassignment remain backlog items.
