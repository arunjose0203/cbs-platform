# Bank transaction limits: initial implementation

The bank detail page now opens Transaction limits. Configure a currency, operation, per-transaction cap and daily total, then submit for independent approval. The existing approval inbox records the before/after values. The maker cannot approve their own change. Configuration version checks reject stale approvals.

API: `GET` and `POST /api/v1/admin/tenants/{bankId}/transaction-limits`. POST requires an Idempotency-Key UUID and a body containing expectedVersion, currency, operation, perTransaction, dailyTotal and reason. Supported operations are FUNDING, TRANSFER, WITHDRAWAL, FUNDING_REVERSAL and HOLD. Amounts are decimal strings. Limits respect currency decimal precision; daily total must be at least the positive per-transaction cap.

Checks occur during submission and again at approval. Existing financial services hold the tenant row lock during approval, serializing limit consumption across accounts. Totals are bank-wide, per currency, operation and bank business date. They include every approved instruction, including failed or pending deliveries. Posting retries reuse the original instruction and consume no additional allowance. Reversals do not restore the original operation's allowance. Hold release is exempt so limits cannot prevent releasing funds. Decreasing a policy does not undo previously approved transactions.

Control migration V15 adds the policy table and approval action. No bank database migration is required for this increment.

## Remaining controls

Transactions without an approved bank-wide policy for their currency and operation are rejected with TRANSACTION_LIMIT_REQUIRED at submission and approval. The portal lists missing policies across enabled currencies. Pending limit requests do not authorize transactions. No live financial limits are invented or seeded. RELEASE remains exempt.

Branch-specific and individual staff limits, account-to-branch ownership, and operational staff submission/review are implemented for assigned account operations; see branch-operations.md. Customer funding still requires a platform administrator. These limits cover customer funding and operations; they do not claim to cap manual GL journals or external provider payments.

## Statements

Customer statement dialogs now offer Export CSV. The export includes opening/closing balances and all posted movements in the selected interval, up to 10,000 entries. Larger results fail explicitly. Authorization and ownership checks are shared with the statement viewer. The data comes from one repeatable-read bank database transaction and the export is audited. Text is quoted and spreadsheet formula prefixes are neutralized. Delivery by email/SMS and PDF rendering remain pending.
