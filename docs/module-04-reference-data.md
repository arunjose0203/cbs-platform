# Module 04: currencies, languages and business calendars

Open **Bank settings**, choose a bank, then select **Currencies**, **Languages** or
**Business calendar**. Changes are submitted to the Module 03 Approval Inbox and
take effect only after a different platform administrator approves them. A single
request captures the complete settings snapshot; another bank change makes it
stale. User interface language preferences are personal and apply immediately.

## Currency settings

Each bank maintains its enabled currencies and rounding modes. The currency
catalogue and decimal precision come from the installed Java runtime's currency
data. Precision is not freely editable: JPY uses zero decimal places, INR/KES two,
and BHD three in the current runtime. Catalogue entries with undefined precision
are excluded. This catalogue is reference data, not a live exchange-rate or
currency-validity feed; review it when upgrading the runtime.

Rounding uses decimal strings and `BigDecimal`, never binary floating point.
Supported rules are HALF_EVEN, HALF_UP, HALF_DOWN, UP, DOWN, CEILING and FLOOR.
The preview accepts at most 24 integer and 12 fractional digits, without grouping
separators or exponent notation. Every legal entity's functional currency must
remain enabled. Enable a new functional currency through approval before adding
an entity that uses it. FX conversion, cash denomination rounding and ledger
posting are later modules; this preview does not book a transaction.

## Language settings

Bank languages are canonical BCP 47 tags whose base language is present in the
runtime's ISO language catalogue, such as `en`, `en-KE`, `fr`, `sw` or `hi-IN`.
Duplicate tags are rejected. The default must be in the bank's supported list.
Approval updates the existing tenant default, keeping the two screens consistent.
The old bank-profile endpoint now rejects direct default-language changes.

The interface selector supports English, French and Swahili. Preferences belong
to the authenticated staff identity and persist in the control database. Changing
this preference does not grant access or change the staff concurrency version.
New settings screens and navigation labels are translated; older screens,
catalogue names and backend error messages use English fallback. A bank's configured
language list is business reference data and does not imply a complete UI
translation exists for every listed language. Initial user preference is English.

## Business calendar

The bank calendar has a timezone, explicit business date, weekend weekdays and
named fixed-date holidays. Weekdays use ISO numbering: Monday=1 through Sunday=7.
At least one weekday must remain available. Duplicate holiday dates and invalid
timezones are rejected. Holidays are maintained by administrators; country
holidays are not automatically imported or inferred.

The business date can only move forward or remain unchanged and must be a working
day in the proposed calendar. Supported input dates are 1900–2200. The date preview
shows whether a date is a working day, a holiday name where applicable, and the
next working date strictly after the supplied date. Weekend and holiday rules
are evaluated as local dates, avoiding fixed-24-hour assumptions at DST changes.

The bank's operational calendar is separate from each legal entity's recorded
timezone. Initialization copies the oldest legal entity's timezone. Initial
weekends are Saturday/Sunday, with the first weekday on or after the local
initialization date as the business date. No midnight scheduler changes it.
Approval-controlled date configuration is not end-of-day processing; journals,
accruals, cutoffs and closing prerequisites will arrive in Module 12.

## Storage and authorization

Flyway V5 adds `platform.bank_settings`, backfills existing tenants, adds a staff
language preference and enables the `BANK_SETTINGS` approval action. New bank
registration initializes settings in the same transaction. Each bank gets its
own row keyed by tenant UUID; currencies are initialized from its legal entities.
No bank-database schema migration is required.

Platform administrators manage settings. Read-only bank routes require active
membership in the requested bank, even for platform administrators. Caller-supplied
tenant headers cannot grant access. Mutation and approval audit records commit
atomically. No credentials or bearer tokens are stored in settings or approvals.

## API

Paths are relative to `/api/v1`.

| Method and path | Purpose |
| --- | --- |
| `GET /admin/reference-data/catalogue` | Currency precision, language codes, timezones, rounding modes |
| `GET /admin/tenants/{id}/settings` | Full settings and current tenant version |
| `PUT /admin/tenants/{id}/settings` | Submit full settings; UUID `Idempotency-Key`; HTTP 202 |
| `GET /admin/tenants/{id}/calendar-day?date=2026-12-25` | Working-day preview |
| `GET /admin/tenants/{id}/rounding-preview?currency=KES&amount=123.455` | Decimal rounding preview |
| `GET /banks/{id}/reference-data` | Bank-member read access |
| `GET /access/preferences` | Own interface language |
| `PUT /access/preferences` | Set own language, e.g. `{"language":"sw"}` |

Settings submission includes `expectedVersion`, `currencies` (code, roundingMode,
enabled), `languages`, `defaultLanguage`, `businessTimezone`, `businessDate`,
`weekendDays`, `holidays` (date, name), and `reason`. Lists are bounded to 200
currencies, 30 language tags, six weekend weekdays and 1,000 holidays. The portal's
bank selector shows the first 100 banks; opening settings from bank detail also
supports a bank outside that first page.

## Verification and next steps

Integration tests cover approval before effect, precision for zero/two/three-digit
currencies, signed amounts, invalid input, required functional currency,
default-language validation, bypass prevention, bank membership/isolation, stale
approvals, holidays/weekends, leap dates, forward-only dates and private user
preferences. Browser fixtures can be launched with
`node ui/test/approval-preview.mjs --settings`; they never contact live databases.

Before a pilot, review each bank's settings, enter its actual holidays and test
one real maker/checker approval. Accounts and posting modules must add their own
currency-in-use restrictions before disabled currencies can affect live accounts.
