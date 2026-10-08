# Loan arrears and collections — 8 October 2026

The lending lifecycle was exercised in isolated PostgreSQL databases with fictional borrowers and separate maker/checker JWT identities before this increment was implemented. This validates application behaviour; it is not bank-led production acceptance or a successful interactive sign-in to the Neon development bank.

## Operator workflow

1. An administrator prepares storage; operating staff require explicit bank/branch lending grants (see [roles and approval limits](bank-scoped-lending-access.md)). Use **Lending → Prepare storage** or **Loan arrears & collections → Prepare storage** to apply bank migration V21. Control migration V36 is applied on application startup.
2. Complete credit policy, limits, application, independent assessment/conditions and disbursement in the existing lending workflow. Financial fees additionally require explicit per-currency limits for `LOAN_FEE_ASSESS`, `LOAN_FEE_PAY` and `LOAN_FEE_WAIVE`. No amounts or limits are seeded.
3. Accrue loans through the current business date in Lending. The collections page marks incomplete accrual and pending financial delivery. Pending openings have no reconciled receivable yet.
4. Open **Loan arrears & collections → Arrears & case**. Review unpaid due dates and separate principal, interest and fees in CURRENT, 1–30, 31–60, 61–90, 91–180 and 181+ calendar-day buckets. Each loan retains its own currency.
5. **Update case → Assign** an active registered staff identity and follow-up date. Record contacts with evidence and a reason. Set explicit escalation levels (team lead, credit manager or legal review); these are tracking labels, not automatic legal action or outbound communication.
6. Record a promise to pay with amount and due date. It must be positive, no more than recorded outstanding debt, and use currency precision. Cancel an open/broken promise with an auditable reason before replacing it. A new promise after fulfilment starts with a fresh payment baseline.
7. Use **Fees** only for an explicit charge supported by the borrower agreement. First assessment fixes a dedicated, unused debit-normal asset ledger and fee-income ledger for the loan. Preview then submit for independent approval by the authorized credit reviewer. Subsequent fees use that same book. Pay or waive a selected outstanding fee through the same approval process.
8. Ordinary loan repayment continues to pay posted interest then principal. **Fee payment is separate** and debits the linked customer account. Review posting failures/retry and immutable financial history in Lending, and case history in the collections page. Legacy contact notes remain under Lending → Collections.
9. Resolve a case only after current accrual, zero overdue amounts and no unfulfilled promise. An accounting write-off is not legal forgiveness and cannot automatically resolve recovery. Reopen with a reason, active owner and follow-up date when required.

## Accounting and ageing rules

- Equal-principal monthly contractual dates retain the original calendar anchor, including month-end dates. Principal prepayments reduce the earliest unpaid instalments. No projected future interest becomes a receivable.
- Actual posted accruals are allocated to monthly due dates using the same actual-day basis, HALF_EVEN rounding and fractional carry as the posting engine. Posted interest before the next due date remains current. After contractual maturity, additional posted interest falls due daily. Banks must accept this product rule or request an alternative schedule policy before rollout.
- A due date becomes overdue the next calendar day. No grace-period, holiday shift, interest suspension or punitive rate is inferred. KES uses two currency decimals; RWF/UGX use zero. Money remains decimal strings/BigDecimal throughout.
- Assessment: debit fee receivable, credit fee income. Payment: debit customer deposit liability, credit fee receivable. Waiver: debit fee income, credit fee receivable. Customer balance, journal and immutable fee receipt post in one bank transaction.
- Partial fee payments/waivers reduce the original assessment; remaining debt keeps its original due date. Fee balances prevent loan closure, restructuring and write-off until paid or independently waived. Impairment currently covers principal plus interest, not fee exposure.
- Promises derive from actual posted loan repayments and fee payments after their captured baseline. Reversed repayments no longer count. Waivers, restructuring and accounting write-offs never count as cash. States distinguish OPEN, BROKEN, FULFILLED and FULFILLED_LATE; payment reversals can reopen a previously fulfilled promise.
- Dedicated fee ledgers are protected against manual journal use. Bank constraints reconcile the fee subledger to its asset balance. Day close checks fee balances alongside journal/customer/contract reconciliation and blocks pending financial approvals/deliveries.
- Independent approval, current business date/version, explicit transaction limits, account eligibility, available funds, immutable records and safe posting retries are enforced. A failed bank transaction rolls back all its effects; a lost acknowledgement can be retried without a second debit.

## Storage and API

Control V36 adds immutable `platform.loan_collection_event`, `platform.loan_fee_book`, new approval/limit/event kinds and assessment-reference uniqueness. Bank V21 adds immutable `bank.loan_fee_event`, accounting constraints and dedicated-ledger protection. Financial events continue to use contract/journal delivery and their original approval/audit trails.

Bank-scoped administrator endpoints:

- `GET /api/v1/admin/tenants/{bank}/loan-collections?limit=25&offset=0`
- `GET /api/v1/admin/tenants/{bank}/loan-collections/{loan}?limit=25&offset=0`
- `POST /api/v1/admin/tenants/{bank}/loan-collections` with `Idempotency-Key`
- `POST /api/v1/admin/tenants/{bank}/contracts/fees/preview`
- `POST /api/v1/admin/tenants/{bank}/contracts/fees` with `Idempotency-Key`

Case updates lock the bank, check its version, and atomically append the event, snapshot and audit record. Exact retries return without duplicate history; changed payloads under the same key fail. Lists/history are paged. Online arrears replay has an explicit 10,000-completed-event limit per contract; it fails visibly rather than silently truncating financial history.

## Remaining acceptance and extensions

This is a bounded operational release. Collections-specific bank/branch roles, portfolio search/assignment queues, scheduled escalation delivery, borrower SMS/email, legal recovery workflows, post-write-off recoveries, fee-payment reversals, fee taxation, fee impairment, automatic penalty policy, backdated/value-date corrections and scalable materialized arrears snapshots remain future work. The new screen uses English operational labels; full translated content still needs language acceptance.

Country-specific regulatory classification, IFRS 9 automation, disclosures/consumer-protection rules and approved fee treatment for Kenya, Uganda and Rwanda must be supplied and accepted by the bank. Security, concurrency/load, restoration/failover and bank-led UAT remain production gates. No real borrower decisions, financial test transactions, credit limits or MFA bypass were created in Neon for this validation.
