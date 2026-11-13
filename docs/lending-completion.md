# Round 15: Lending Completion Documentation

## Executive Summary
Round 15 completes the lending and loan lifecycle capabilities of the Core Banking System (CBS) across functional matrix rows **F30–F32** and acceptance criteria **A30–A32**. It delivers regulatory and prudential lending enhancements including:
1. **Credit Bureau Integration & Reporting**: Standardized CRB inquiry requests, deterministic scoring (300–850) with risk grading (AA to E), and statutory CBK/IFRS 9 monthly portfolio returns.
2. **Non-Accrual & Moratoria**: Suspension of interest accrual recognition into P&L for non-performing loans (>90 DPD, Stage 3), and formal loan moratoria (interest-only and full payment holidays) freezing arrears ageing.
3. **Partial Write-Off & Recovery**: Ability to write down distressed carrying loan amounts against loan loss allowances, and post-write-off recovery crediting designated recovery income ledgers.
4. **Automated IFRS 9 ECL Staging**: Automated classification into Stage 1 (1.00%), Stage 2 (3.00%), and Stage 3 (20.00%) with required allowance computations.
5. **Fee Taxation & Reversal**: Automated 20% excise duty on assessed loan fees and reversal of loan fee payments (`LOAN_FEE_REVERSAL`) with dual control limits.
6. **Arrears Snapshots**: Efficient point-in-time arrears position snapshots (`platform.loan_arrears_snapshot`) to bypass 10,000-event replays.
7. **Verified Collateral Document Storage**: Seamless verification linked with KYC customer document storage (`platform.customer_document`).

---

## 1. Credit Reference Bureau (CRB) Integration & Reporting (F30)

### CRB Inquiry Service
- **Endpoint**: `POST /api/v1/credit/bureau/inquire`
- **Supported Bureaus**: Metropol, TransUnion, Creditinfo.
- **Scoring Engine**: Evaluates applicant attributes deterministically:
  - Base score: 650.
  - Salary-to-debt ratio, credit inquiries, KYC identity, past default flags.
  - Normalized range: 300 to 850.
- **Risk Grades**:
  - `AA`: Excellent (750–850)
  - `A`: Good (700–749)
  - `B`: Fair (650–699)
  - `C`: Marginal (600–649)
  - `D`: High Risk (500–599)
  - `E`: Impaired / Default (<500)
- **Persistence**: Recorded in immutable table `platform.credit_bureau_inquiry`.

### Statutory Monthly Portfolio Return
- **Endpoint**: `GET /api/v1/credit/bureau/report`
- **Classification Mapping**:
  - `PERFORMING` (DPD = 0): Stage 1
  - `WATCH` (DPD 1–30): Stage 1 / Stage 2 boundary
  - `SUBSTANDARD` (DPD 31–90): Stage 2
  - `DOUBTFUL` (DPD 91–180): Stage 3 (Non-performing / Non-accrual)
  - `LOSS` (DPD > 180): Stage 3

---

## 2. Non-Accrual & Moratoria (F30, F31)

### Non-Accrual Accounting (CBK / IFRS 9)
- When a loan reaches **90 Days Past Due (DPD)**, it is classified as Stage 3 (Non-Performing).
- Subsequent contractual interest accruals (`Kind.ACCRUE`) advance the contract's accrual date, but do **not** post interest income debit/credit entries to the general ledger, preventing artificial revenue inflation on defaulted assets.

### Loan Moratorium
- **Endpoint**: `POST /api/v1/contracts/moratoria`
- **Moratorium Types**:
  - `FULL_PAYMENT_HOLIDAY`: Suspends both principal and interest obligations.
  - `INTEREST_ONLY`: Requires interest servicing while freezing principal amortisation.
- **Arrears Treatment**: During the moratorium validity period, covered instalments are excluded from overdue calculations. DPD remains 0 and ageing bucket is `CURRENT`.
- **Database Table**: `platform.loan_moratorium`.

---

## 3. Partial Write-Off & Recovery (F31)

### Partial Write-Off
- **Operation**: `PARTIAL_WRITE_OFF` (`Kind.PARTIAL_WRITE_OFF`).
- **Accounting Treatment**:
  - Debit: `Loan Loss Allowance (Balance Sheet Contracct)`
  - Credit: `Loan Principal Asset (Balance Sheet)`
- **Carrying Value**: Directly reduces outstanding principal without terminating the loan contract.

### Post-Write-Off Recovery
- **Operation**: `RECOVERY` (`Kind.RECOVERY`).
- **Accounting Treatment**:
  - Debit: `Cash / Settlement Account`
  - Credit: `Bad Debt Recovery Income (P&L)`
- Operates under strict dual-control limits (`LOAN_RECOVERY` operation check).

---

## 4. Automated IFRS 9 ECL Staging (F31)

### Staging Rules & Rates
- **Stage 1 (Performing)**: DPD $\le 30$ $\rightarrow$ Provision rate: **1.00%**
- **Stage 2 (Underperforming)**: $30 < \text{DPD} \le 90$ $\rightarrow$ Provision rate: **3.00%**
- **Stage 3 (Impaired)**: DPD $> 90$ $\rightarrow$ Provision rate: **20.00%**

### Evaluation Endpoint
- **Endpoint**: `POST /api/v1/contracts/ecl/evaluate`
- Evaluates active loan contracts, computes expected credit losses against carrying balances, and determines incremental adjustments needed against existing allowances.

---

## 5. Loan Fee Excise Duty & Reversal (F32)

### 20% Excise Duty on Fees
- Under Kenyan financial tax law, fees assessed on credit facilities attract 20% excise duty.
- When `LoanFeeService.assess(...)` executes, an automated tax component is derived:
  $$\text{Tax Amount} = \text{round}(\text{Fee Amount} \times 0.20, 2)$$
- The resulting journal entry credits the designated `taxLedger` alongside fee income.

### Fee Payment Reversal (`LOAN_FEE_REVERSAL`)
- Reversal of fee payments (`Kind.FEE_PAY`) is enabled through `ContractService.reverse(...)`.
- Restores unpaid fee balances and assesses authority through `LOAN_FEE_REVERSAL` transaction limits.

---

## 6. Arrears Snapshots & Document Verification (F30, F32)

### Arrears Snapshots
- `platform.loan_arrears_snapshot` stores precalculated DPD and balance positions at historical cutoffs.
- Allows collection queries and delinquency scanning to scale past 10,000 historical events without unbounded replay latency.

### Verified Collateral Documents
- `CreditService.verifyCollateralDocument` binds collateral items to verified KYC files stored in `platform.customer_document`, ensuring loan collateral documents are indexed and audited.

---

## Acceptance Verification (A30–A32)
- Unit and integration tests in `src/test/java/com/cbs/contracts/LendingCompletionTest.java`:
  - `moratoriumSuspendsArrearsAging`: Verified.
  - `partialWriteOffReducesCarryingAmount`: Verified.
  - `creditBureauScoringAndAssetClassification`: Verified.
  - `ifrs9EclStagingAndAllowanceCalculation`: Verified.
  - `loanFeeExciseTaxAndReversal`: Verified.
  - `nonAccrualSuspendsInterestRecognition`: Verified.
  - `postWriteOffRecoveryAccounting`: Verified.
