# Module 06.1: Deposit Product Factory

The Product Factory defines bank-specific savings and current-account products.
It prepares configuration for future customer account opening; it does not open
accounts or move customer funds.

## Workflow

1. In Chart of Accounts, approve an active LIABILITY / POSTING account with a
   CREDIT normal balance. Enable the intended currency in Bank settings.
2. Open **Product Factory**, select the bank and choose **Create product**.
3. Enter a unique product code, type, currency, liability chart mapping, name,
   description, minimum opening amount, minimum balance and reason. New forms
   default to Inactive. Select Active when the definition is ready for approval.
4. Submit. The product is not created or changed until a different active
   administrator approves the **Deposit product** request in Approval Inbox.
5. Use **Manage** to change the name, description, minimum amounts or active
   status. Each approved change creates an immutable revision. **Revisions**
   displays the approved rules, status, reviewer, timestamp and audit reference.

The list supports bank selection, search, type/status filters and pagination.
English, French and Swahili labels are provided. The bank selector lists the first
100 banks; the bank detail page also links to that bank's Product Factory.

## Rules and storage

- Codes are unique per bank, with 1–32 uppercase letters, digits, underscores or
  hyphens. Code, type, currency and liability mapping are immutable after creation;
  use a new product code for a different structure.
- Each product has one currency. A bank can offer multiple products in different
  enabled currencies. Products are bank-wide; entity and branch eligibility are
  not configured in this increment.
- Minimum amounts are nonnegative decimal strings, with up to 18 whole digits
  and the currency's permitted fractional precision (maximum six decimal places
  in storage). Minimum opening must be at least minimum balance. No rounding or
  floating-point conversion occurs. These rules will be enforced on customer
  transactions by the later account module; no customer transaction engine is
  provided here.
- New products require an enabled currency and active credit-normal liability
  posting account from the same bank. Active products prevent deactivation of
  that chart account and disabling their currency. Inactive products can retain
  disabled dependencies, but reactivation revalidates them.
- The bank must be ACTIVE to submit or approve changes. An approved day close
  blocks product mutations until it completes. Read access remains available.
- Creation, updates, activation and deactivation all require maker/checker
  approval. Bank version checks reject stale decisions; retries use UUID
  idempotency keys. A changed request body requires a new key.
- Control migration V10 adds `platform.deposit_product` and
  `platform.deposit_product_revision`. Composite bank/account foreign keys enforce
  mapping scope; a trigger rejects revision updates/deletes. Product changes,
  revision snapshots, bank version, approval decision and audit commit together.
- No new bank-database migration is needed. Future customer accounts should pin
  an approved product revision; later revisions must not silently rewrite their
  contracted terms. Account migration requires a separate designed workflow.

## API

All endpoints require a platform administrator and use
`/api/v1/admin/tenants/{bankId}/products`:

- `GET ?search=&type=SAVINGS|CURRENT&active=true|false&limit=25&offset=0`
  returns approved products, total and bank configuration version. Filters are optional.
- `POST` with UUID `Idempotency-Key` and JSON `expectedVersion`, nullable
  `productId`, `code`, `name`, `description`, `productType`, `currency`,
  `minimumOpening`, `minimumBalance`, `liabilityAccountId`, `active`, `reason`.
  Returns 202 and the pending approval request. Amounts must be JSON strings.
- `GET /{productId}/revisions?limit=25&offset=0` returns approved snapshots newest
  first. Unknown or cross-bank product IDs return 404.

## Verification and remaining work

79 backend and 17 UI tests pass, including new checks for separate approval,
idempotent concurrent submission, transaction rollback, stale/rejected changes,
exact amounts above JavaScript's safe-integer range, immutable revisions,
cross-bank mappings, dependency guards, HTTP validation, permissions and UI
escaping. Browser verification uses isolated fictional data.

No product was created in a live bank during implementation. Live acceptance with
two staff identities remains pending. Interest/rate tiers, fees, overdrafts,
eligibility rules, scheduled effective dates, translated product names and customer
terms migration remain future Product Factory extensions. Deposit account opening
follows customer onboarding; this module alone does not provide end-to-end retail banking.
