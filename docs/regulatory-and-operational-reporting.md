# Regulatory and Operational Reporting (Round 14)

**Scope & Governance:** Central Bank of Kenya (CBK) Prudential Guidelines, IFRS 9 Financial Instruments, KRA Tax Procedures Act (WHT & Excise), Functional Matrix rows F09 and F19  
**Service:** `com.cbs.reporting.OperationalReportService`, `com.cbs.reporting.ReportingController`  
**Dependencies:** Builds upon Round 12 (`com.cbs.contracts.LoanArrears`, `com.cbs.reporting.TableOutput`)  

---

## 1. Executive Summary

Round 14 delivers comprehensive operational, regulatory, and statutory tax reporting capabilities for tenant banks:
1. **Deposit Register (`/deposit-register`)**: Complete register of active customer deposit accounts, mapped to customer identification, products, posted balances, and branch custody.
2. **Loan Register (`/loan-register`)**: Portfolio register computing exact Days Past Due (DPD) and overdue instalments using `LoanArrears.calculate`. Classifies loans into performing and Non-Performing Loans (NPL, >90 DPD) in accordance with CBK guidelines.
3. **Cash Custody Report (`/cash-report`)**: Summarizes physical vault, teller till, and ATM custody holdings across branches with currency breakdown and verification statuses.
4. **Tax Remittance Report (`/tax-remittance`)**: Calculates statutory Kenya Revenue Authority (KRA) obligations across financial servicing runs:
   - **Withholding Tax (WHT)**: 15% statutory withholding on gross customer interest credited.
   - **Excise Duty**: 20% statutory excise duty on transactional fee income.
5. **Exception Ageing & Unresolved Items (`/exception-ageing`)**: Centralized surveillance of unresolved operational discrepancies across teller cash shortages, unapplied payments, failed outbox deliveries, and active compliance cases, bucketed into statutory ageing intervals (`0–7 days`, `8–30 days`, `31–60 days`, and `61+ days`).
6. **Loan Provisions & IFRS 9 Staging (`/loan-provisions`)**: Implements CBK and IFRS 9 impairment classification:
   - **Stage 1 (Performing)**: DPD $\le 30$ days $\rightarrow$ 1% general provision allowance.
   - **Stage 2 (Underperforming / Watch)**: DPD $31$ to $90$ days $\rightarrow$ 3% watch provision allowance.
   - **Stage 3 (Non-Performing / Credit-Impaired)**: DPD $> 90$ days $\rightarrow$ 20% (Substandard / Doubtful) to 100% (Loss) recommended provision.
7. **Multi-Format Export Engine**: Immediate download as structured JSON, sanitized CSV (formula-injection neutralized), or paginated A4 PDF via `TableOutput`.

---

## 2. API Endpoints & Access Control

All operational reporting endpoints are rooted under `/api/v1/admin/tenants/{bankId}/reporting/` and mapped to the `FINANCE` duty in `BankDutyRoutes`:

| Path | Query Parameters | Description |
| :--- | :--- | :--- |
| `GET /deposit-register` | `currency`, `branchId`, `format=json\|csv\|pdf` | Customer accounts, product code, posted balance, branch |
| `GET /loan-register` | `currency`, `format=json\|csv\|pdf` | Loan contracts, borrower name, principal, arrears, DPD, NPL |
| `GET /cash-report` | `branchId`, `format=json\|csv\|pdf` | Vaults and teller tills, cash book balance, physical status |
| `GET /tax-remittance` | `from`, `to`, `format=json\|csv\|pdf` | Servicing interest, 15% WHT, fees assessed, 20% excise duty |
| `GET /exception-ageing` | `format=json\|csv\|pdf` | Cash, payment, and compliance discrepancies bucketed by age |
| `GET /loan-provisions` | `currency`, `format=json\|csv\|pdf` | IFRS 9 Stage 1/2/3 staging, gross balance, allowances, net carrying |

Every report access is audited to `platform.audit_event` with caller subject, tenant version, and timestamp.

---

## 3. Statutory & Financial Logic

### 3.1. Loan Arrears & DPD Classification
```
DPD = max(0, businessDate - earliestUnpaidInstalmentDueDate)
NPL = DPD > 90 days
```
- Outstanding principal, interest, carry, and overdue amounts are evaluated chronologically against the bank's posted business date.
- Arrears buckets are evaluated as `CURRENT`, `1-30`, `31-60`, `61-90`, `91-180`, `181+`.

### 3.2. Tax Remittance
- **WHT Basis**: Servicing interest credited to customer deposit accounts $\times 15\%$.
- **Excise Duty Basis**: Service fees, ledger fees, and transaction charges assessed $\times 20\%$.

### 3.3. IFRS 9 / CBK Prudential Staging
- **Stage 1 (Normal)**: 12-month ECL, evaluated as 1.00% of gross carrying amount.
- **Stage 2 (Watch)**: Significant Increase in Credit Risk (SICR), evaluated as 3.00% of gross carrying amount.
- **Stage 3 (Default)**: Lifetime ECL, evaluated as 20.00% ($91 \le \text{DPD} \le 180$) to 100.00% ($\text{DPD} > 180$).

---

## 4. Verification & Testing

- **Backend Unit & Integration Tests (`OperationalReportServiceTest.java`)**:
  - `nonAdminIsForbidden`: Asserts fail-closed role enforcement (`ACCESS_DENIED`).
  - `depositRegisterSummarizesBalancesAndGeneratesTable`: Verifies balance aggregation, customer name mapping, and table column generation.
  - `cashReportSummarizesLocationsAndCurrencies`: Tests vault/till cash positions and multi-currency totals.
  - `taxRemittanceCalculatesWithholdingAndExciseDuty`: Validates exact 15% WHT and 20% excise duty calculations over date windows.
  - `exceptionAgeingBucketsDiscrepanciesProperly`: Validates `0-7d`, `8-30d`, `31-60d`, and `61+d` discrepancy distribution.
  - `loanProvisionsClassifiesIFRS9Stages`: Verifies IFRS 9 staging, allowance percentages, and net carrying amounts.
- **UI Tests (`ui/test/report.test.mjs`)**:
  - Verified operational reports dialog with dropdown selection for all 6 reports.
  - Verified PDF, CSV, and JSON download dispatch.
