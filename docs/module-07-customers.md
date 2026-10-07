# Module 07.1: Individual Customer Onboarding

This increment registers and maintains individual customer profiles. It records
manual review references and approval decisions; it does not perform identity
verification, sanctions screening or a jurisdiction-specific compliance assessment.
Customer accounts and transactions remain Module 08 and later work.

## First use

1. Sign in as a platform administrator and select **Customers**, then the bank.
   For an existing bank, choose **Prepare customer storage**, enter a reason, and
   apply bank migration V5. This does not create customers.
2. Choose **Register customer**. Enter the legal name, birth date, nationality,
   residence country/address, international phone number and optional email.
3. Supply a National ID or Passport reference: issuing country, document number,
   optional expiry, and an opaque reference to your approved document repository.
   This module does not upload, download or dereference document files or URLs.
4. Leave the customer as **Prospect** until review is ready. **Active** requires a
   manual review reference and an unexpired document if an expiry was entered.
   **Blocked** is also available through approval. This is a customer-level status;
   financial account restrictions will be implemented with accounts.
5. Supply a reason and submit. A different active administrator reviews the
   **Customer change** in Approval Inbox and approves or rejects it.
6. Approval creates an **Awaiting delivery** record. The worker normally applies
   it within 30 seconds. Refresh until delivery reads **Applied**. Resolve any
   reported database issue and use **Retry delivery** when needed.
7. Use **Details** to inspect the full profile (this view is audited) or **Manage**
   to request changes. Pending deliveries must finish before another edit.

Customer numbers are system-generated immutable identifiers (`C` followed by a
UUID without hyphens). They are not deposit-account numbers. Search accepts a
customer name or number; the directory is paginated and filterable by status.
English, French and Swahili labels are available. The bank detail screen links
directly to the selected bank's customers, including banks beyond the first 100
shown by the selector.

## Duplicate and validation controls

- A document number, normalized to uppercase with spaces/hyphens removed, is
  unique within its bank, document type and issuing country. This applies even to
  blocked customers. Exact-document duplicates cannot be overridden by a reason.
- A matching name (case/spacing normalized) and birth date requires a nonblank
  duplicate-review explanation. The operator must investigate the existing
  record and the reviewer sees the explanation. This is an exact-match check,
  not fuzzy matching, transliteration, biometrics or cross-bank identity search.
- Dates of birth must fall between 1900 and today (UTC). Country codes must be
  ISO alpha-2 codes. No adult/minor policy is inferred; the bank must define
  eligibility and guardian workflows in a later increment.
- Submission and approval revalidate the same rules under a bank write lock.
  Intervening bank changes make requests stale. Maker/checker separation and
  idempotency follow the existing approval system. Rejected/cancelled requests
  do not create or change the customer.
- Manual review references are supplied by staff; there is no external repository
  integration to validate that the referenced evidence exists. Activation does
  not certify KYC/AML compliance. Expiry is checked when changes are approved,
  not continuously; periodic review/expiry processing remains future work.

## Persistence and recovery

Control V11 adds a bank-scoped duplicate/number registry and durable delivery
queue. The full proposed/approved profile exists in the control approval payload
and delivery history as well as the bank database after delivery. Identity numbers
are masked in approval comparison values; directory rows omit document numbers,
birth dates and contact details. Full detail requires a platform administrator and
records a `CUSTOMER_PROFILE_VIEWED` audit event.

Bank V5 adds `bank.customer` and append-only `bank.customer_event`. Every connection
verifies database ownership. A version-checked bank transaction writes both the
profile and request receipt. Lost control acknowledgement can be retried without
duplicating the customer or event. Control status becomes Applied only after the
bank transaction succeeds. Suspended banks pause delivery until resumed.

The latest control definition and an Applied bank profile must match when viewing
or editing details; drift fails explicitly rather than silently overwriting data.
Future account opening must require an ACTIVE customer with APPLIED delivery and
verify the authoritative bank profile. A pending Active change is not eligibility
to open an account. Customer work does not post financial journals or change the
business date.

This development implementation does not add field-level encryption, document
storage, retention/purge workflows, corporate customers, beneficial owners,
customer-specific roles, automated screening or periodic KYC review. Access to
the shared approval database is sensitive because it contains profile history.
Production privacy, retention, access and security review are still required.

## API

Base: `/api/v1/admin/tenants/{bankId}/customers`, platform-administrator access.

- `GET ?search=&state=PROSPECT|ACTIVE|BLOCKED&limit=25&offset=0`: summaries,
  bank configuration version, storage readiness and delivery status.
- `GET /{id}`: full profile and delivery status; audited and bank-scoped.
- `POST /prepare`: `expectedVersion`, `reason`; returns 204 after upgrading storage.
- `POST`: UUID `Idempotency-Key`; body `expectedVersion`, nullable `customerId`,
  `profile`, `duplicateReview` and `reason`; returns 202 and an approval request.
- `POST /{requestId}/retry`: retries an approved delivery; 204. Refresh to inspect
  its result. A retry response alone does not imply successful delivery.

Profile fields: `fullName`, `birthDate`, `nationality`, `residenceCountry`, `address`,
`phone`, `email` (empty string allowed), `documentType` (`NATIONAL_ID` or `PASSPORT`),
`documentCountry`, `documentNumber`, nullable `documentExpiry`, `documentReference`,
`state`, and `reviewReference` (empty except for Active). Dates use YYYY-MM-DD.

## Verification

82 backend tests and 20 UI tests pass. New checks cover separate approval,
masked review values, duplicate identity normalization, possible-duplicate review,
manual activation requirements, invalid dates, scope/access, approval rollback,
concurrent retries, bank commit before control acknowledgement, immutable bank
events, status updates, rejected changes, directory privacy and detail audit.
Browser checks use only fictional data on an isolated server. Live acceptance with
two real staff identities remains pending; no live customers are seeded.
