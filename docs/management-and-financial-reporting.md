# Management and financial reporting — 11 November 2026

This change follows [period close and signer capture](period-close-and-signer-capture.md). It covers matrix rows F09 and F19 (reporting expansion). Bank acceptance remains pending.

All reports are read-only, administrator-only, and per legal entity and currency. Nothing is translated between currencies.

## Financial statements

**Balance sheet** as at any date, with a comparative date (by default one year earlier):
- The layout follows the bank's chart of accounts: groups, then posting accounts, with zero branches omitted.
- Equity shows the **current period result not yet closed** (income less expenses still in the books). A year-end close moves it into retained earnings.
- The report flags whether assets = liabilities + equity in both columns.

**Income statement** for any period of up to 5 years, with a comparative period (by default the same dates one year earlier):
- Year-end closing journals are left out of movements, so a closed year still shows its full income and expenses.

**How balances are built.** Every figure comes from posted journal lines up to the date, read in one repeatable-read snapshot. Past dates are therefore exact, not just current balances.

**Formats:** JSON, CSV (formulas neutralized, signed amounts kept) and A4 PDF (amounts never truncated, page numbers).

**Endpoints:**
- `GET …/reporting/balance-sheet?legalEntityId&currency&asOf[&compareTo][&format]`
- `GET …/reporting/income-statement?legalEntityId&currency&from&to[&compareFrom&compareTo][&format]`

## Management dashboard

`GET …/reporting/dashboard`, and the portal page **Management reports**, shows the current business date:

- **Deposits:**
  - totals per currency
  - by product and by branch
  - the ten largest depositors, with their share of deposits (concentration)
- **Loan quality:**
  - outstanding amounts in the buckets current / 1–30 / 31–60 / 61–90 / 91–180 / 181+ days past due
  - the non-performing ratio (more than 90 days)

  Ageing replays posted loan events with the same calculation as collections. Loans that can't be reconciled are counted and left out, and the dashboard says so. Up to 5,000 active loans are included, and anything beyond that is flagged.
- **Cash** in each vault and till.
- **Work waiting:**
  - pending approvals by type
  - failed postings by queue
  - queued and failed alerts
  - open compliance, cash-exception and payment cases

Each view is audited.

**Not included yet:** provisions and allowances (they are bank-specific accounting entries; use a report definition over the allowance ledgers) and capital adequacy ratios (these need the regulator's risk weights).

## Report definitions (groundwork for regulatory returns)

A definition is a code, name and basis (**balances** as at a date, or **movements** over a period) plus a list of lines. Each line is a code, a label, a set of chart accounts and a sign (+ or −).
- Group accounts include all their sub-accounts.
- Changes go through the Approval Inbox (`REPORT_DEFINITION`).
- Running a definition returns each line's amount as JSON, CSV or PDF.

**CBK returns are not hard-coded.** Once the bank or a compliance contact provides the official templates, each return can be set up as one approved definition, mapped from the bank's own chart. Returns that need data outside the general ledger, such as counts or customer breakdowns, will need extra work.

## Database

Control **V43** adds the `REPORT_DEFINITION` approval action and `platform.report_definition`. The bank schema is unchanged.

## Verification

- `financialStatementsDefinitionsAndDashboardReconcileToTheLedger` covers:
  - a balanced balance sheet (assets 1,679.6 = liabilities 1,000 + equity 500 + result 179.6) against an empty comparative
  - the income statement (300 / 120.4 / 179.6)
  - filter errors, and 401/403 access checks
  - CSV and PDF content, and the unsupported-format rejection
  - definition validation and self-approval denial
  - the exact signed line results, and CSV run output
  - dashboard deposit totals and depositor share
- The year-end close test now also checks that after closing, the year's income statement still shows 350 / 130 / 220, and the balance sheet shows retained earnings 220 with a zero current result.
- `TableOutputTest` checks CSV formula neutralization and PDF pagination with full-length amounts. A UI test covers the dashboard escaping, the loan buckets and definition-line parsing.
