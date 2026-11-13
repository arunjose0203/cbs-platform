# Localization and Accessibility — Round 17

Round 17 of the [go-live plan](go-live-plan-2026-11-13.md) delivers complete localization for the Kenyan pilot configuration (English and Kiswahili, plus French) and verifies accessibility compliance (Gate E14, Matrix row F33).

---

## 1. Scope & Reference Configuration

The reference implementation for the Kenyan pilot (Demo Bank Kenya) requires dual English/Kiswahili capability across all administrative and branch functions:

- **Languages offered**:
  - `en`: English (Default administrative interface)
  - `sw`: Kiswahili (National/official language for Kenyan operations)
  - `fr`: French (East African Community / regional partner operations)
- **Interface Coverage**:
  - 100% parity across all 32 views in the administration portal.
  - Every navigation label, table column header, form label, modal title, helper text, and operational action button is translated.
  - Critical banking and regulatory error messages (e.g. dormant accounts, deceased customer fail-closed states, duty segregation, legal holds) are localized.
  - Receipts, PDF statement indicators, and alert templates reflect locale selections.

---

## 2. Architecture & Implementation

### Translation Engine (`ui/src/i18n.js`)
- **Key Registry**: Over 710 distinct translated phrases per supported non-English language.
- **Dynamic Language Selection**: Driven by `<select id="ui-language">` in the top bar. Automatically updates `document.documentElement.lang` and re-renders navigation labels and active workspace views without full page reloads.
- **Fail-safe Fallback**: Unrecognized strings or unsupported language tags safely fall back to the exact source English key without throwing runtime exceptions.
- **Structured Values Formatter**: `displayValue(val)` safely formats primitives, null/undefined placeholders (`—`), and JSON arrays/objects for approval reviews and audit logs.

### Complete Screen Coverage
The localization dictionary covers all CBS functional modules:
1. **Platform Foundation**: Bank directory, Staff & access, Approval inbox, Bank settings, Chart of accounts, Ledgers, Journals, Accounting reports, Day close, Product factory.
2. **Access Governance (Round 9)**: Bank duties, maker/checker segregation, access reviews, lockout protection.
3. **Branch & Cash Custody (Rounds 6-7)**: Branch requests, teller tills, vault balances, physical denomination counts, interbranch cash transit, shortages/overages.
4. **Lending & Servicing (Rounds 4-5)**: Credit applications, loan origination, repayment schedules, accruals, restructuring, impairment allowances, collections & arrears buckets, promise-to-pay.
5. **Treasury & FX (Round 8)**: FX trades, multi-currency conversion, closing rates, revaluation books.
6. **Payments & M-Pesa (Round 5)**: Payment orders, Daraja sandbox payouts, M-Pesa statement imports, payment exception cases, returns.
7. **Compliance & AML (Round 11)**: Screening lists, UN XML lists, periodic re-screening, beneficiary screening, STR draft exports, monitoring alerts.
8. **Deposit Lifecycle & KYC (Round 12)**: Encrypted document storage, periodic KYC reviews with risk ratings, dormancy candidate detection, unclaimed assets, deceased customer workflow, closure settlement reversals.
9. **Privacy & Data Protection (Round 18)**: Data-subject requests, maker-checker legal holds, statutory retention matrix, archival review candidates, personal data view logs.

---

## 3. Automated Verification

An automated translation completeness test suite in `ui/test/i18n.test.mjs` enforces translation quality in CI:

1. **Parity Enforcement**: Asserts that `translations.fr` and `translations.sw` contain identical key sets (710+ keys) with zero missing keys and non-empty translations for every string.
2. **Navigation Parity**: Verifies all 32 portal navigation items translate distinctly in English, French, and Kiswahili.
3. **Operational Journey Verification**: Tests translations of operational terms across cash custody, branch operations, subject requests, legal holds, and arrears buckets.
4. **Statutory Error Translation**: Confirms fail-closed error messages (dormant accounts, deceased customer, duty lockout) render localized messages in the UI notice banner.
5. **Display Value Formatting**: Tests formatting of nulls, arrays, and JSON payloads.

Result: **129/129 UI unit tests passing**.

---

## 4. Accessibility Pass (Gate E14 / WCAG 2.1 AA)

- **Semantic HTML Structure**:
  - Semantic layout: `<header class="topbar">`, `<aside class="sidebar">`, `<main id="content" role="main">`, `<footer class="page-footer">`.
  - Navigation landmarks with `aria-label="Administration"`.
- **Keyboard Navigation**:
  - Skip link at top of document (`<a class="skip" href="#content">Skip to content</a>`) allows keyboard users to bypass sidebar navigation.
  - Native `<dialog id="dialog">` modal management with `dialog.showModal()`, focus trapping, `lastFocus` restoration upon closing, and `Escape` key dismissal.
- **Screen Reader Support**:
  - Notice banner configured as `role="status" aria-live="polite"`.
  - Form validation errors marked with `role="alert"`.
  - Table headers explicitly scoped with `<th scope="col">`.
  - Decorative artwork hidden from assistive tech via `aria-hidden="true"`.
- **Low-Bandwidth Optimization**:
  - Zero heavy external dependencies or web fonts.
  - Compact inline SVG icons (`viewBox="0 0 40 40"`).
  - Total compressed UI assets under 100 KB, ensuring fast operation over low-bandwidth branch connections.

---

## 5. Exit Criteria & Gate E14 Sign-off

- [x] Kiswahili translations implemented for all operational screens.
- [x] French translations implemented for regional alignment.
- [x] Automated test fails if any translation key is missing in an offered language.
- [x] Keyboard navigation, skip-link, and ARIA roles verified.
- [x] Acceptance scenario A33 verified for English and Kiswahili.
