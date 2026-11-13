# Round 16: FX and Term-Deposit Completion Documentation

## Executive Summary
Round 16 completes foreign exchange (FX) and term-deposit lifecycle capabilities of the Core Banking System (CBS) across functional matrix rows **F26–F28** and acceptance criteria **A26–A28**. It delivers:
1. **Partial Term-Deposit Closure**: Enables depositors to withdraw a portion of their deposit principal prior to maturity, assessing approved early-closure penalties and withholding taxes on the settled interest, while keeping the reduced principal active and accruing interest until maturity.
2. **Standing Renewal Instructions**: Allows configuring automated renewal actions for term deposits upon maturity (`AUTOMATIC_RENEWAL_CAPITALIZE`, `AUTOMATIC_RENEWAL_PAYOUT`, `TRANSFER_TO_ACCOUNT_AT_MATURITY`).
3. **Cross Rates Derivation**: Mathematically rigorous derivation of cross exchange rates between two non-base foreign currencies via the bank's functional pivot currency, compounding bid/ask spreads correctly:
   $$\text{Buy} = \frac{b_1}{s_2}, \quad \text{Mid} = \frac{m_1}{m_2}, \quad \text{Sell} = \frac{s_1}{b_2}$$
4. **External Rate Feeds**: Staging and maker-checker approval for central bank (e.g. CBK) and market rate feeds before activating them into the bank's live rate catalogue.
5. **FX Valuation Corrections**: Enables reversing prior erroneous valuation runs on the open business date with balanced reversing journal entries (`FXVC-`), unsealing the trading book to permit corrected valuation runs.
6. **Full-Bank Monetary Item Translation (IAS 21)**: Automated translation of foreign-currency denominated balance sheet assets and liabilities at closing spot rates, reporting net unrealized translation gains/losses and balance sheet exposure.

---

## 1. Partial Term-Deposit Closure (F28, A28)

### Operation: `PARTIAL_CLOSE`
- **Model**: `TermServicingModels.Operation.PARTIAL_CLOSE`
- **Endpoint**: `POST /api/v1/admin/tenants/{bank}/contracts/servicing`
- **Validation**:
  - `closeAmount > 0` and `closeAmount < currentPrincipal`.
  - Business date must be before maturity date.
  - Accrual must be up to date (`accruedThrough == businessDate`).
  - Penalty amount $\le$ accrued interest.
- **Accounting Treatment**:
  - Outstanding principal decreases: $\text{Principal}_{after} = \text{Principal}_{before} - \text{closeAmount}$.
  - Deposit contract remains active (`closed = false`).
  - Accrued interest is settled through the withdrawal date.
  - **Journal Entries (Balanced)**:
    - **Debit**: Dedicated Principal Ledger (`closeAmount`)
    - **Debit**: Dedicated Accrued Interest Ledger (`interest`)
    - **Credit**: Customer Account Liability Ledger (`closeAmount + netInterest`)
    - **Credit**: Tax Payable Ledger (`tax`)
    - **Credit**: Early Closure Penalty Income Ledger (`penalty`)
- **Transaction Limit**: Evaluated under `TD_PARTIAL_CLOSE`.

---

## 2. Standing Renewal Instructions (F28, A28)

### Supported Modes:
1. `AUTOMATIC_RENEWAL_CAPITALIZE`: At maturity, net interest is added to principal, and the deposit is rolled over for the configured period.
2. `AUTOMATIC_RENEWAL_PAYOUT`: At maturity, net interest is credited to the customer account, and the original principal is renewed.
3. `TRANSFER_TO_ACCOUNT_AT_MATURITY`: At maturity, principal and net interest are redeemed and transferred to the customer account.

### Endpoints:
- `POST /api/v1/admin/tenants/{bank}/contracts/standing-instructions`
- `GET /api/v1/admin/tenants/{bank}/contracts/{id}/standing-instruction`
- `GET /api/v1/admin/tenants/{bank}/contracts/standing-instructions`
- **Table**: `platform.term_standing_instruction`

---

## 3. Cross Exchange Rates (F26, A26)

### Triangulation Formula
For foreign currencies $C_1$ (base) and $C_2$ (quote) quoted against pivot currency $P$ with rates $(b_1, m_1, s_1)$ and $(b_2, m_2, s_2)$:
- **Mid Rate**:
  $$\text{Mid} = \frac{m_1}{m_2}$$
- **Bank Buy Rate** (Bank buys $C_1$, sells $C_2$):
  $$\text{Buy} = \frac{b_1}{s_2}$$
- **Bank Sell Rate** (Bank sells $C_1$, buys $C_2$):
  $$\text{Sell} = \frac{s_1}{b_2}$$

### Endpoint:
- `GET /api/v1/admin/tenants/{bank}/fx/cross-rate?base={C1}&quote={C2}`
- Preserves $0 < \text{Buy} \le \text{Mid} \le \text{Sell}$ strictly at 8 decimal places.

---

## 4. FX Rate Feeds (F26, A26)

### Feed Workflow
1. **Staging**: Rate feed batch uploaded from provider (e.g. Central Bank of Kenya, Reuters) via:
   `POST /api/v1/admin/tenants/{bank}/fx/feeds`
2. **Review & Approval**: Maker cannot approve own feed. Independent checker approves:
   `POST /api/v1/admin/tenants/{bank}/fx/feeds/{id}/approve`
3. **Activation**: Feed items are atomically validated against currency calendars and saved to `platform.fx_rate`.

---

## 5. FX Valuation Corrections (F27, A27)

### Correction Workflow
- If an approved valuation run on the current open business date used an incorrect closing rate or inputs, it can be corrected:
  `POST /api/v1/admin/tenants/{bank}/fx/valuation/runs/{id}/correct?reason={reason}`
- **Mechanics**:
  1. Verifies business date equals current open business date.
  2. Generates reversing journal entry (`FXVC-` + uuid) with swapped Debit/Credit lines, restoring original ledger balances.
  3. Records audit trail in `platform.fx_valuation_correction`.
  4. Deletes / unseals the valuation record in `platform.fx_valuation`, allowing the corrected valuation to be executed.

---

## 6. Foreign-Currency Monetary Translation (IAS 21) (F27, A27)

### Balance Sheet Translation
- Scans all active posting ledgers of the bank whose denomination is not the legal entity's functional currency.
- Evaluates current open balance (debit vs credit).
- Translates balance to functional currency at the approved closing rate.
- Aggregates total foreign assets and total foreign liabilities.
- Evaluates net translation exposure and unrealized foreign exchange gain/loss under IAS 21.
- **Endpoint**: `GET /api/v1/admin/tenants/{bank}/fx/valuation/monetary-translation`
- **Table**: `platform.monetary_translation_run`

---

## Verification & Acceptance
- Unit & integration tests in `TermDepositCompletionTest` and `FxCompletionTest`.
- UI coverage across contracts, FX, and FX valuation interfaces.
- Zero regressions across existing suites.
