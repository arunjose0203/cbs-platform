# Graph Report - cbs-platform  (2026-11-13)

## Corpus Check
- 429 files · ~230,832 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 16 file(s) not represented in the graph (top: (none) 6, .service 3, .timer 2)

## Summary
- 4475 nodes · 15883 edges · 187 communities (133 shown, 54 thin omitted)
- Extraction: 83% EXTRACTED · 17% INFERRED · 0% AMBIGUOUS · INFERRED: 2666 edges (avg confidence: 0.85)
- Token cost: 536,679 input · 0 output

## Community Hubs (Navigation)
- Teller Cash Store
- Shared Java Imports
- Service Admin Policies
- M-Pesa Statement Import
- Access Service Core
- FX Valuation
- Chunked Servicing
- Financial Statements
- Controllers and Conditional Beans
- Credit Assessment
- HTTP Test Harness
- Approval Delivery Flow
- Reference Data Service
- Customer Store
- Account Closure
- Portal App Shell
- Approval Actions Enum
- Journal and Day Close Delivery
- Test Database Fixtures
- Request Identity
- Approval Grants and Policy
- Compliance Cases
- Bank Roles and Tenancy
- Staff Roles
- Contract Calculations
- CI Verify Workflow
- Notifications
- Customer and Account UI
- Identity Configuration
- Integration Test Context
- Payment Workspace
- Test Plan and Gates
- Branches
- Account Mandates
- FX Trading API
- Shared Utility Imports
- Go-Live and Market Plan
- Period Close Store
- Branch Operations Workspace
- Contract Corrections API
- Account and Funding Detail
- Development Roadmap
- Background Workers
- UI Build Tooling
- FX Trade Store
- Progress and Maker-Checker
- Accounting Reports
- Encrypted Backup Recovery
- UI i18n and Tests
- Loan Collections
- Lending Access Grants
- Screening and Monitoring
- M-Pesa Gateway
- Ops Python Jobs
- HTTP Filters and Errors
- Bank Duties
- Security Configuration
- Project Rules (CLAUDE.md)
- CI Security Workflow
- Payment Case Capabilities
- Production Guards
- Filter and Config Tests
- Runtime Config Example
- Branch and Cash UI
- Access Models
- Approval API
- Tenant API
- Contract and Fee Entries
- Loan Arrears
- Reversals and TD Servicing Docs
- Recovery Tests
- Compliance Automation
- Bank Database Provisioner
- Account and Funding Stores
- Journal Store
- Customer Statements
- Production Config
- Test JWT Identity
- Guard and Gateway Tests
- Approval Inboxes
- Branch Limits Enforcement
- Day Close Store
- Model Imports
- Chart of Accounts
- Name Matcher
- FX UI
- Compose Postgres
- Day Close Docs
- M-Pesa Reconciliation Docs
- Migration Runner
- Statement PDF
- Operation Models
- Contracts UI
- Daraja Sandbox Gateway
- Rate Limiter
- Retry Endpoints
- Payment Reconciliation
- Access Controller
- Compose Hardening
- Local Identity Docs
- Ledger and Journal API
- Table Output
- Duty Catalogue
- Operation Store
- Payment Case Decisions
- App Config Flyway
- Bank Duties Round Doc
- Product Docs
- Lending Operations
- Statement CSV
- Term Deposit Servicing
- Payment API
- Tenant DB Config
- Compose Services
- Compose Containers
- Competitive Gap Report
- Go-Live Rounds 10-12
- Dependabot
- Development MFA Policy
- Bank Duties Tests
- Statement UI
- Prometheus Alerts
- Module 01 Tenant API Docs
- Staging Up Tests
- Contract Store
- Report UI
- Branch and Lending Limits Docs
- Screening and Customer Docs
- Mandates and Signers Docs
- Deployment and Recovery Docs
- Monitor Tests
- Statement API
- Health Probes
- Bank Duties UI
- Optimistic Versioning Docs
- Contract Service
- Loan Servicing Models
- Portal Config
- Load Probe Tests
- Staging Compose
- Reporting Docs
- Approval Inbox Docs
- Funding Docs
- Receipt Recovery Docs
- Chart UI
- Compliance UI
- Journal UI
- Servicing UI
- Teller Cash Docs
- Alerts and Guard Docs
- Session Config
- Datasource Config
- Error Config
- Collections UI
- Statement Import UI
- Compose Logging
- M-Pesa Callback
- OAuth Config
- Actuator Config
- Credit UI
- Payment Cases UI
- Compose Environment
- Staging Preflight
- Application Entry
- Bank Duty API
- Denominations
- Payment Roles
- Inventory Capacity Test
- Portal Index Page
- Staging Up Script
- Ops Requirements
- Round 13 M-Pesa Go-Live
- Round 14 Regulatory
- Round 17 Localization
- Round 18 Privacy
- Round 19 Data Migration
- Maven Project

## God Nodes (most connected - your core abstractions)
1. `Actor` - 378 edges
2. `Tenant` - 297 edges
3. `TenantAdministrationIntegrationTest` - 246 edges
4. `ApprovalService` - 194 edges
5. `Request` - 142 edges
6. `TenantRepository` - 121 edges
7. `AccessService` - 96 edges
8. `ReferenceDataService` - 92 edges
9. `t()` - 87 edges
10. `TenantException` - 86 edges

## Surprising Connections (you probably didn't know these)
- `Non-negotiable invariants` --semantically_similar_to--> `Idempotency-Key on approval POSTs`  [INFERRED] [semantically similar]
  docs/architecture.md → CLAUDE.md
- `Administration sidebar navigation (data-view buttons)` --conceptually_related_to--> `Closure check API GET /accounts/{id}/closure`  [AMBIGUOUS]
  src/main/resources/static/admin/index.html → docs/w02-w03-functional-validation.md
- `CBS platform notes for Claude Code` --references--> `CBS application progress`  [EXTRACTED]
  CLAUDE.md → docs/application-progress.md
- `Bank duty enforcement` --conceptually_related_to--> `Maker-checker rule (ApprovalService)`  [INFERRED]
  docs/bank-duties.md → CLAUDE.md
- `Independent clearance review (COMPLIANCE_REVIEW)` --implements--> `Maker-checker rule (ApprovalService)`  [INFERRED]
  docs/compliance-cases.md → CLAUDE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Segregation-of-duties controls across banks, branches and lending** — docs_bank_duties_duty_enforcement, docs_bank_scoped_lending_access_lending_role_matrix, docs_branch_operations_branch_staff_limits, claude_maker_checker_rule, docs_compliance_cases_independent_clearance [INFERRED 0.85]
- **Receipt-based retry and lost-acknowledgement recovery** — docs_bank_scoped_lending_access_revocation_and_retries, docs_competitive_gap_report_2026_10_07_control_to_bank_delivery_protocol, docs_architecture_single_transaction_commit, claude_idempotency_key [INFERRED 0.75]
- **Lending lifecycle: policy, application, servicing, impairment, collections** — docs_lending_controls_credit_policy, docs_lending_controls_credit_application, docs_fx_term_deposits_lending_equal_principal_loan, docs_lending_controls_restructuring, docs_lending_controls_impairment, docs_lending_controls_write_off, docs_loan_arrears_collections_ageing_buckets, docs_loan_arrears_collections_loan_fee_book [INFERRED 0.85]
- **Bank acceptance framework (F rows, A cases, G gates, W packages, scope decisions)** — docs_functional_gap_acceptance_matrix_2026_10_09_module_matrix, docs_functional_gap_acceptance_matrix_2026_10_09_acceptance_scenarios, docs_functional_gap_acceptance_matrix_2026_10_09_shared_gates, docs_functional_gap_acceptance_matrix_2026_10_09_work_packages, docs_functional_gap_acceptance_matrix_2026_10_09_scope_decision, docs_go_live_plan_2026_11_13_acceptance_phase [EXTRACTED 1.00]
- **Go-live functional rounds 9-19** — docs_go_live_plan_2026_11_13_round_9, docs_go_live_plan_2026_11_13_round_10, docs_go_live_plan_2026_11_13_round_11, docs_go_live_plan_2026_11_13_round_12, docs_go_live_plan_2026_11_13_round_13, docs_go_live_plan_2026_11_13_round_14, docs_go_live_plan_2026_11_13_round_15, docs_go_live_plan_2026_11_13_round_16, docs_go_live_plan_2026_11_13_round_17, docs_go_live_plan_2026_11_13_round_18, docs_go_live_plan_2026_11_13_round_19 [EXTRACTED 1.00]
- **Maker-checker approval actions routed through Approval Inbox** — docs_module_03_approvals_approval_inbox, docs_module_04_reference_data_bank_settings, docs_module_05_chart_of_accounts_chart_account_action, docs_module_05_day_close_day_close, docs_management_and_financial_reporting_report_definition [INFERRED 0.85]
- **Control-to-bank outbox delivery with idempotent receipts** — docs_module_05_ledger_accounts_delivery_outbox_worker, docs_module_05_journals_two_transaction_acknowledgement, docs_module_05_day_close_day_close, docs_module_05_journals_journal_delivery [INFERRED 0.85]
- **Module 05 accounting core (chart, ledgers, journals, reports, day close)** — docs_module_05_chart_of_accounts_doc, docs_module_05_ledger_accounts_doc, docs_module_05_journals_doc, docs_module_05_reports_doc, docs_module_05_day_close_doc [EXTRACTED 1.00]
- **M-Pesa payout, reconciliation and exception lifecycle** — docs_payments_mpesa_sandbox_mpesa_b2c_sandbox, docs_mpesa_statement_reconciliation_mpesa_statement_import, docs_payment_lifecycle_exceptions_payment_exceptions, docs_payment_lifecycle_exceptions_provider_returns [EXTRACTED 1.00]
- **Product to customer to account to funding pipeline** — docs_module_06_products_deposit_product_factory, docs_module_07_customers_individual_customer_onboarding, docs_module_08_accounts_deposit_account_opening, docs_module_08_funding_funding_and_balances, docs_module_08_operations_account_operations [EXTRACTED 1.00]
- **Conditions that block business-day close** — docs_servicing_chunked_batches_sealed_business_day, docs_period_close_and_signer_capture_period_close, docs_teller_cash_management_cash_exception_cases, docs_payment_lifecycle_exceptions_settlement_carry_forward, docs_mpesa_statement_reconciliation_statement_closure [INFERRED 0.85]
- **Account closure lifecycle (bounded closure to final settlement)** — docs_w02_w03_functional_validation_zero_balance_account_closure, docs_w03_closure_settlement_closure_settlement_operation, docs_w02_w03_functional_validation_bank_migration_v22, docs_w03_closure_settlement_bank_migration_v23, docs_w03_closure_settlement_forfeited_carry, docs_w03_closure_settlement_account_pending_closure, docs_testing_cbs_test_plan_2026_10_13_fixture_c1_bounded_closure [INFERRED 0.85]
- **W02/W03 acceptance evidence chain** — docs_testing_cbs_test_plan_2026_10_13, docs_testing_w02_w03_acceptance_2026_10_13, docs_w02_w03_functional_validation, docs_w03_closure_settlement, docs_testing_readme [EXTRACTED 1.00]
- **Reversal operation kinds** — docs_transaction_reversals_cash_reversal, docs_transaction_reversals_withdrawal_reversal, docs_transaction_reversals_transfer_reversal, docs_transaction_reversals_full_opposite_journal_reversal [EXTRACTED 1.00]

## Communities (187 total, 54 thin omitted)

### Community 0 - "Teller Cash Store"
Cohesion: 0.06
Nodes (30): BankCashStore, CashController, CashExceptionInput, CashModels, Change, Count, Dispatch, Entry (+22 more)

### Community 2 - "Service Admin Policies"
Cohesion: 0.07
Nodes (5): Run, TypeReference<List<String>>, Preference, Grant, Actor

### Community 3 - "M-Pesa Statement Import"
Cohesion: 0.06
Nodes (29): MpesaStatementCsv, StatementImportController, Action, Kind, BOOK, CLEAR_CONFLICT, CLOSE, DUPLICATE (+21 more)

### Community 4 - "Access Service Core"
Cohesion: 0.10
Nodes (20): AccessService, BranchOperations, BranchService, BankLedgerStore, ChartService, DayCloseService, JournalService, LedgerService (+12 more)

### Community 5 - "FX Valuation"
Cohesion: 0.06
Nodes (17): BankFxValuationStore, FxValuationController, Book, ClosingRate, CostBasis, WEIGHTED_AVERAGE, Dispatch, Flow (+9 more)

### Community 6 - "Chunked Servicing"
Cohesion: 0.06
Nodes (17): BankServicingStore, Snapshot, ServicingController, Batch, Calculation, Chunk, Dispatch, Exclusion (+9 more)

### Community 7 - "Financial Statements"
Cohesion: 0.06
Nodes (20): FinancialStatementService, Chart, LedgerBalances, Snapshot, Agg, ManagementDashboardService, ReportDefinitionService, ReportingController (+12 more)

### Community 8 - "Controllers and Conditional Beans"
Cohesion: 0.05
Nodes (18): TransactionLimitController, ChartController, DayCloseController, JournalController, PeriodCloseController, PeriodCloseService, FundingController, FundingService (+10 more)

### Community 9 - "Credit Assessment"
Cohesion: 0.08
Nodes (18): CreditController, Application, Assessment, CreditModels, Decision, APPROVE, DECLINE, REVOKE (+10 more)

### Community 10 - "HTTP Test Harness"
Cohesion: 0.09
Nodes (9): CurrencyRule, AddLegalEntityRequest, AuditEvent, LegalEntityInput, MembershipRequest, UpdateRequest, StatementPdfTest, FoundationHttpTest (+1 more)

### Community 12 - "Reference Data Service"
Cohesion: 0.06
Nodes (18): Catalogue, Change, Product, ProductModels, Revision, Type, CURRENT, SAVINGS (+10 more)

### Community 13 - "Customer Store"
Cohesion: 0.07
Nodes (21): BankCustomerStore, CustomerController, Change, CustomerModels, CustomerType, INDIVIDUAL, ORGANIZATION, Definition (+13 more)

### Community 14 - "Account Closure"
Cohesion: 0.08
Nodes (17): AccountClosureService, Check, Change, Definition, Directory, State, ACTIVE, AWAITING_FUNDING (+9 more)

### Community 15 - "Portal App Shell"
Cohesion: 0.08
Nodes (57): accountFields, accountUi, action(), activeChoices, api(), approvalDetail(), approvalDirectory(), badge() (+49 more)

### Community 16 - "Approval Actions Enum"
Cohesion: 0.04
Nodes (55): Action, ACCOUNT_BRANCH, ACCOUNT_FUNDING, ACCOUNT_MANDATE, ACCOUNT_OPERATION, BANK_DUTY, BANK_DUTY_POLICY, BANK_RESUME (+47 more)

### Community 17 - "Journal and Day Close Delivery"
Cohesion: 0.07
Nodes (10): Change, Account, Change, Definition, Directory, Dispatch, LedgerModels, Activation (+2 more)

### Community 18 - "Test Database Fixtures"
Cohesion: 0.07
Nodes (3): DatabaseIntegrationTest, RateLimitHttpTest, TestDatabases

### Community 20 - "Approval Grants and Policy"
Cohesion: 0.06
Nodes (3): DecisionInput, Request, Dismiss

### Community 21 - "Compliance Cases"
Cohesion: 0.09
Nodes (17): ComplianceController, Category, KYC_REVIEW, SCREENING_ALERT, TRANSACTION_REVIEW, ComplianceModels, Decision, CLEARED (+9 more)

### Community 22 - "Bank Roles and Tenancy"
Cohesion: 0.08
Nodes (17): BankRole, BANK_AUDITOR, BANK_OPERATOR, Binding, ChangeRequest, LegalEntity, Membership, RegisterRequest (+9 more)

### Community 23 - "Staff Roles"
Cohesion: 0.12
Nodes (5): Me, Staff, StaffInput, StaffUpdate, DutyUser

### Community 24 - "Contract Calculations"
Cohesion: 0.14
Nodes (11): Calculation, ContractModels, Definition, Dispatch, Instalment, Open, Position, Type (+3 more)

### Community 25 - "CI Verify Workflow"
Cohesion: 0.05
Nodes (43): concurrency, cancel-in-progress, group, CBS_DB_PASSWORD, CBS_DB_URL, CBS_DB_USER, CBS_INTEGRATION_TESTS, POSTGRES_DB (+35 more)

### Community 26 - "Notifications"
Cohesion: 0.07
Nodes (19): Channel, EMAIL, NONE, SMS, Event, CREDIT, DEBIT, Movement (+11 more)

### Community 27 - "Customer and Account UI"
Cohesion: 0.11
Nodes (34): createAccountUi(), action(), render(), statusName(), createCustomerUi(), action(), render(), customerFields (+26 more)

### Community 28 - "Identity Configuration"
Cohesion: 0.09
Nodes (7): AccessConfiguration, Settings, ClientConfiguration, PortalController, Settings, IdentityConfiguration, TenantModuleConfiguration

### Community 31 - "Test Plan and Gates"
Cohesion: 0.07
Nodes (40): CBS functional and production acceptance test plan, Control gates G01-G08, Execution statuses (NOT RUN/PASS/FAIL/BLOCKED/NOT IMPLEMENTED/EXCLUDED), Fixture C1 bounded closure, Fixture D1 deposits, Fixture R1 recovery, Fixture S1 daily servicing, FOCUS-01..14 focused regressions (+32 more)

### Community 32 - "Branches"
Cohesion: 0.14
Nodes (5): Branch, BranchGrant, BranchInput, BranchUpdate, Assignment

### Community 33 - "Account Mandates"
Cohesion: 0.08
Nodes (22): AccountParty, AccountRole, GUARDIAN, JOINT_HOLDER, SIGNATORY, Mandate, MandateModels, Parties (+14 more)

### Community 34 - "FX Trading API"
Cohesion: 0.09
Nodes (10): FxController, FxModels, Quote, QuoteInput, Rate, Side, BANK_BUYS_BASE, BANK_SELLS_BASE (+2 more)

### Community 36 - "Go-Live and Market Plan"
Cohesion: 0.07
Nodes (34): Rwanda, Uganda and Kenya release work, Seven-step implementation sequence and acceptance gates, Target markets Rwanda, Uganda, Kenya, Enterprise readiness review 7 October 2026, Compliance-after-approval and servicing eligibility race fixes, FATF Recommendations and PEP guidance, Module assessment table (controls vs enterprise gaps), OWASP Application Security Verification Standard (+26 more)

### Community 37 - "Period Close Store"
Cohesion: 0.09
Nodes (18): BankPeriodCloseStore, LedgerBalance, Change, Dispatch, Entry, Line, Side, CREDIT (+10 more)

### Community 40 - "Contract Corrections API"
Cohesion: 0.11
Nodes (6): ContractController, Correction, Event, Posting, Instruction, LoanServicingService

### Community 42 - "Development Roadmap"
Cohesion: 0.08
Nodes (31): Development roadmap, Module delivery sequence 00-20, External OpenID Connect provider for portal, Payment lifecycle delivery sequence, Evidence index E01-E15, FX, term deposits and lending - first operational release, Fixed-rate equal-principal loan, Approved FX rate book (buy/mid/sell) (+23 more)

### Community 43 - "Background Workers"
Cohesion: 0.09
Nodes (4): LedgerDeliveryWorker, ComplianceWorker, Event, MonitoringService

### Community 44 - "UI Build Tooling"
Cohesion: 0.06
Nodes (28): esbuild, oidc-client-ts, root, dependencies, oidc-client-ts@3.5.0, devDependencies, esbuild@0.28.2, name (+20 more)

### Community 45 - "FX Trade Store"
Cohesion: 0.13
Nodes (7): BankFxStore, Batch, Dispatch, FxTradeModels, Reversal, Trade, FxTradeService

### Community 46 - "Progress and Maker-Checker"
Cohesion: 0.07
Nodes (33): ApprovalService, CBS application progress, Chunked daily processing, W03 closure settlement, Functional Gap & Acceptance Matrix, Inter-branch cash in transit, Lending controls and credit assessment, Loan arrears and collections (+25 more)

### Community 47 - "Accounting Reports"
Cohesion: 0.09
Nodes (9): AccountingReportController, AccountingReportService, Kind, LEDGER_STATEMENT, RECONCILIATION, TRIAL_BALANCE, Report, ReportCsv (+1 more)

### Community 48 - "Encrypted Backup Recovery"
Cohesion: 0.19
Nodes (18): backup(), check_inventory(), connection(), digest(), environment(), Fence, financial_checks(), fingerprint() (+10 more)

### Community 49 - "UI i18n and Tests"
Cohesion: 0.12
Nodes (19): displayValue(), setLanguage(), translations, createProductUi(), action(), render(), typeLabel(), createScreeningUi() (+11 more)

### Community 50 - "Loan Collections"
Cohesion: 0.10
Nodes (19): LoanCollectionsController, Action, ASSIGN, CANCEL_PROMISE, CONTACT, ESCALATE, PROMISE, REOPEN (+11 more)

### Community 51 - "Lending Access Grants"
Cohesion: 0.14
Nodes (10): Grant, LendingAccess, Limit, Role, AUDITOR, COLLECTIONS_OFFICER, CREDIT_APPROVER, CREDIT_OFFICER (+2 more)

### Community 52 - "Screening and Monitoring"
Cohesion: 0.13
Nodes (7): ItemType, MONITORING_ALERT, SCREENING_HIT, Index, Active, Row, ScreeningService

### Community 53 - "M-Pesa Gateway"
Cohesion: 0.20
Nodes (3): MpesaGateway, Order, PaymentService

### Community 54 - "Ops Python Jobs"
Cohesion: 0.13
Nodes (13): atomic(), main(), interrupted(), unit(), main(), main(), percentile(), run() (+5 more)

### Community 56 - "Bank Duties"
Cohesion: 0.18
Nodes (5): BankDuties, Grant, Policy, Review, TenantException

### Community 57 - "Security Configuration"
Cohesion: 0.11
Nodes (3): AccessPolicyFilter, SecurityConfiguration, IdentityUrlPolicyTest

### Community 58 - "Project Rules (CLAUDE.md)"
Cohesion: 0.09
Nodes (19): CBS platform notes for Claude Code, TenantAdministrationIntegrationTest, TestDatabases, Accounting module, Control plane vs per-bank database, Core banking architecture, Modular application boundaries, Non-negotiable invariants (+11 more)

### Community 59 - "CI Security Workflow"
Cohesion: 0.07
Nodes (27): jobs, npm-audit, osv-scan, name, runs-on, steps, timeout-minutes, on (+19 more)

### Community 60 - "Payment Case Capabilities"
Cohesion: 0.18
Nodes (6): Capability, APPROVE, MAKE, READ, Change, PaymentCaseService

### Community 61 - "Production Guards"
Cohesion: 0.12
Nodes (5): Settings, ProductionGuard, RuntimeDatabaseGuard, RuntimeDatabaseGuardIntegrationTest, BankDatabaseProvisioner

### Community 62 - "Filter and Config Tests"
Cohesion: 0.10
Nodes (3): RateLimitFilterTest, jakarta.servlet.http.HttpServlet, BankDatabaseConfigurationTest

### Community 63 - "Runtime Config Example"
Cohesion: 0.08
Nodes (25): password, url, username, databases, cbs, banking, portal, rate-limit (+17 more)

### Community 64 - "Branch and Cash UI"
Cohesion: 0.13
Nodes (17): form(), createBranchUi(), action(), render(), createCashUi(), action(), render(), parseCounts() (+9 more)

### Community 65 - "Access Models"
Cohesion: 0.10
Nodes (13): AccessModels, BranchAccess, BranchRole, AUDITOR, BRANCH_MANAGER, TELLER, Grant, IdentityAudit (+5 more)

### Community 66 - "Approval API"
Cohesion: 0.09
Nodes (18): ApprovalController, ApprovalModels, Decision, APPROVE, CANCEL, REJECT, Details, Event (+10 more)

### Community 68 - "Contract and Fee Entries"
Cohesion: 0.09
Nodes (21): Kind, ACCRUE, EARLY_CLOSE, FEE_ASSESS, FEE_PAY, FEE_WAIVE, IMPAIR, OPEN (+13 more)

### Community 69 - "Loan Arrears"
Cohesion: 0.18
Nodes (6): Amounts, Due, LoanArrears, Report, Totals, LoanArrearsTest

### Community 70 - "Reversals and TD Servicing Docs"
Cohesion: 0.11
Nodes (23): Contract corrections and term-deposit servicing, In-app maturity alerts, Term-deposit early closure, Term-deposit renewal, Bank transaction limits doc, Customer statement CSV export (10,000 entry cap), Bank-wide transaction limit policy, TRANSACTION_LIMIT_REQUIRED rejection (+15 more)

### Community 71 - "Recovery Tests"
Cohesion: 0.16
Nodes (8): age(), check(), authenticated_manifest(), canonical(), decrypt(), encrypt(), RecoveryTests, decrypt()

### Community 72 - "Compliance Automation"
Cohesion: 0.13
Nodes (8): AutomationModels, Escalate, ListCategory, INTERNAL, PEP, SANCTIONS, Upload, ComplianceAutomationController

### Community 73 - "Bank Database Provisioner"
Cohesion: 0.25
Nodes (3): BankDatabaseProvisioner, Settings, Target

### Community 75 - "Account and Funding Stores"
Cohesion: 0.20
Nodes (3): Dispatch, BankAccountStore, BankFundingStore

### Community 76 - "Journal Store"
Cohesion: 0.19
Nodes (4): BankJournalStore, Balance, CREDIT, DEBIT

### Community 77 - "Customer Statements"
Cohesion: 0.18
Nodes (6): Header, Line, Sink, Statement, StatementService, Sink

### Community 78 - "Production Config"
Cohesion: 0.10
Nodes (20): migrations-enabled, cbs, banking, security, hikari, clean-disabled, enabled, validate-on-migrate (+12 more)

### Community 82 - "Branch Limits Enforcement"
Cohesion: 0.18
Nodes (5): Limit, Scope, Change, TransactionLimits, Change

### Community 83 - "Day Close Store"
Cohesion: 0.17
Nodes (8): BankDayCloseStore, TypeReference<List<Total>>, Change, Dashboard, DayCloseModels, Dispatch, History, Total

### Community 85 - "Chart of Accounts"
Cohesion: 0.14
Nodes (11): Account, Category, ASSET, EQUITY, EXPENSE, INCOME, LIABILITY, Chart (+3 more)

### Community 86 - "Name Matcher"
Cohesion: 0.19
Nodes (5): Entry, Match, Name, NameMatcher, NameMatcherTest

### Community 87 - "FX UI"
Cohesion: 0.17
Nodes (8): createFxTradeUi(), createFxUi(), createFxValuationUi(), action(), details(), book, data, posting

### Community 88 - "Compose Postgres"
Cohesion: 0.12
Nodes (16): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, interval, retries, test, timeout, environment (+8 more)

### Community 89 - "Day Close Docs"
Cohesion: 0.14
Nodes (12): Immutable close receipt with closing balances per entity/currency, Day close readiness checks (no pending approvals, reconciled turnover), Module 05.5: Business Day Close, Module 05.3: Journal Posting and Ledger Balances, platform.journal_delivery / platform.journal_ledger (control V8), Manual journal posting (bank.journal, bank.journal_line, bank.ledger_balance; bank V3), Net debit ledger balance, Two-transaction posting with receipt-based acknowledgement recovery (+4 more)

### Community 90 - "M-Pesa Reconciliation Docs"
Cohesion: 0.14
Nodes (15): Control migration V26 (statement imports), M-Pesa statement import and reconciliation, Normalized M-Pesa statement CSV contract, Reconciliation decisions MATCH/LINK/UNLINK/BOOK/REVERSE/DUPLICATE/CLEAR_CONFLICT/SUPERSEDE, STATEMENT_ACTION approval, Statement period closure and reopen, Bank payment roles RECONCILER/SUPERVISOR/AUDITOR, Control migration V27 (payment investigations) (+7 more)

### Community 91 - "Migration Runner"
Cohesion: 0.17
Nodes (5): Database, MigrationMain, Plan, MigrationMainTest, Database

### Community 93 - "Operation Models"
Cohesion: 0.15
Nodes (15): Dashboard, Dispatch, Entry, Item, Kind, CLOSURE_SETTLEMENT, FUNDING_REVERSAL, HOLD (+7 more)

### Community 94 - "Contracts UI"
Cohesion: 0.19
Nodes (8): createContractsUi(), action(), history(), createLendingAccessUi(), bankActions(), signInErrorMessage(), fixture(), fixture()

### Community 96 - "Rate Limiter"
Cohesion: 0.25
Nodes (3): Client, RateLimitFilter, Settings

### Community 98 - "Payment Reconciliation"
Cohesion: 0.16
Nodes (10): Callback, Outcome, FAILED, PAID, PaymentModels, Reconcile, Resolution, MATCH (+2 more)

### Community 100 - "Compose Hardening"
Cohesion: 0.13
Nodes (15): cap_drop, cpus, image, init, mem_limit, networks, pids_limit, read_only (+7 more)

### Community 101 - "Local Identity Docs"
Cohesion: 0.17
Nodes (9): CBS_ALLOW_LOCAL_IDENTITY_HTTP flag, Local development sign-in, Keycloak cbs-dev realm (local identity provider), Neon-hosted CBS administration records, Bootstrap administrator (CBS_BOOTSTRAP_ADMIN_SUBJECT), Module 02: identity, branch access and administration portal, Required access-token claims (iss, aud, sub, exp, iat, sid, auth_time, roles, amr), Session revocation and revocation cutoff (+1 more)

### Community 102 - "Ledger and Journal API"
Cohesion: 0.17
Nodes (3): Directory, Item, LedgerController

### Community 103 - "Table Output"
Cohesion: 0.26
Nodes (3): Table, TableOutput, TableOutputTest

### Community 105 - "Duty Catalogue"
Cohesion: 0.20
Nodes (8): Duty, ACCESS_ADMINISTRATION, BANK_CONFIGURATION, COMPLIANCE, CUSTOMER_OPERATIONS, FINANCE, PAYMENTS, TREASURY

### Community 107 - "Payment Case Decisions"
Cohesion: 0.15
Nodes (13): Decision, ASSIGN, CANCEL, CARRY, RESOLVE, Kind, DISPUTE, PROVIDER_REVERSAL (+5 more)

### Community 108 - "App Config Flyway"
Cohesion: 0.14
Nodes (14): name, fail-on-unknown-properties, clean-disabled, enabled, locations, validate-on-migrate, deserialization, problemdetails (+6 more)

### Community 109 - "Bank Duties Round Doc"
Cohesion: 0.19
Nodes (12): Go-live plan (2026-11-13), Access review attestation (SHA-256 snapshot), Bank duties and access reviews (Round 9), BankDuties.duty exhaustive switch, BankDutiesTest, BankDutyController API (/bank-duties), Control migration V46, Two access administrators lockout guard (BANK_DUTY_LOCKOUT) (+4 more)

### Community 110 - "Product Docs"
Cohesion: 0.17
Nodes (11): Module 06.1 Deposit Product Factory, platform.deposit_product (control V10), Products API /api/v1/admin/tenants/{bankId}/products, bank.customer and bank.customer_event (bank V5), Customer durable delivery queue (control V11), Customer states PROSPECT/ACTIVE/BLOCKED, Module 07.1 Individual Customer Onboarding, platform.account_registry and account_delivery (control V12) (+3 more)

### Community 111 - "Lending Operations"
Cohesion: 0.15
Nodes (13): Operation, CREDIT_RELEASE, CREDIT_REVIEW, LOAN_ACCRUE, LOAN_DISBURSE, LOAN_FEE_ASSESS, LOAN_FEE_PAY, LOAN_FEE_WAIVE (+5 more)

### Community 112 - "Statement CSV"
Cohesion: 0.21
Nodes (3): StatementCsv, StatementService.Sink, StatementCsvTest

### Community 113 - "Term Deposit Servicing"
Cohesion: 0.21
Nodes (12): Details, Instruction, InterestDisposition, CAPITALIZE_NET_INTEREST, PAY_OUT_INTEREST, Operation, EARLY_CLOSE, RENEW (+4 more)

### Community 115 - "Tenant DB Config"
Cohesion: 0.17
Nodes (11): password, url, username, password, url, username, databases, cbs (+3 more)

### Community 116 - "Compose Services"
Cohesion: 0.17
Nodes (12): condition, app, depends_on, environment, image, networks, ports, restart (+4 more)

### Community 117 - "Compose Containers"
Cohesion: 0.17
Nodes (12): cap_drop, command, entrypoint, image, networks, profiles, read_only, security_opt (+4 more)

### Community 118 - "Competitive Gap Report"
Cohesion: 0.17
Nodes (11): FX, term deposits and lending, Production-readiness round, Revocation and receipt-based retries, CBS capability gaps and production improvement report, Competitors: Oracle FLEXCUBE, Temenos Transact (T24), Infosys Finacle, Control-to-bank delivery protocol, Proposed delivery sequence A-F, Engineering and production gaps E01-E15 (+3 more)

### Community 119 - "Go-Live Rounds 10-12"
Cohesion: 0.21
Nodes (11): Bounded statement CSV export (10,000-entry ceiling), Round 10: customer notifications go-live, Round 12: deposit lifecycle completion, Round 15: lending completion, Assessed impairment allowance, Full provided stage-3 write-off, Loan arrears and collections (8 October 2026), Loan collections API (control V36, bank V21) (+3 more)

### Community 120 - "Dependabot"
Cohesion: 0.17
Nodes (11): spring, day, interval, patterns, updates, directory, groups, open-pull-requests-limit (+3 more)

### Community 123 - "Statement UI"
Cohesion: 0.23
Nodes (9): localizeChrome(), getLanguage(), createStatementUi(), action(), show(), currentLanguage(), download(), account (+1 more)

### Community 124 - "Prometheus Alerts"
Cohesion: 0.18
Nodes (10): summary, groups, name, rules, severity, alert, annotations, expr (+2 more)

### Community 125 - "Module 01 Tenant API Docs"
Cohesion: 0.24
Nodes (10): Bank membership (BANK_OPERATOR / BANK_AUDITOR), Module 01: bank tenant administration API, Bank operational-context endpoint, application/problem+json error contract, Bank tenant, Append-only tenant audit trail, Database binding (approved logical connection identifier), Module 01: tenant and bank administration backlog (+2 more)

### Community 128 - "Report UI"
Cohesion: 0.25
Nodes (8): createReportUi(), action(), changed(), render(), reportTable(), createReportingUi(), action(), render()

### Community 130 - "Branch and Lending Limits Docs"
Cohesion: 0.22
Nodes (10): Mandatory transaction policies (fail closed), Per-operation/currency approval authority limits, Bank-scoped lending roles and approval limits, Control migration V37, Legacy approverSubject as policy contact, Account-to-branch assignment, Branch operations and scoped limits, Branch and staff monetary limits (+2 more)

### Community 131 - "Screening and Customer Docs"
Cohesion: 0.29
Nodes (8): Organization customers with directors and beneficial owners, Control migration V45 (screening and monitoring), Monitoring rules: large cash, structuring, velocity, unusual amount, Name matcher (Jaro-Winkler, NameMatcherTest), Screening hit restricting customer, Screening lists (SCREENING_LIST approval), Screening and transaction monitoring, Transaction monitoring scan and alerts

### Community 132 - "Mandates and Signers Docs"
Cohesion: 0.29
Nodes (8): Account mandates (ACCOUNT_MANDATE), Control migration V41 (customer_party, account_mandate), Organizations, joint accounts and minors' accounts, Signing rules any one / any two / all, Month-end and year-end period close, PERIOD_CLOSE_POLICY (fiscal year-end, retained earnings ledger), Signer capture for cash withdrawals and M-Pesa payouts, Year-end closing journal to retained earnings

### Community 133 - "Deployment and Recovery Docs"
Cohesion: 0.27
Nodes (9): ops/monitor.py Prometheus monitoring, Production deployment, backup and disaster recovery, Isolated restore drill (ops/tests/recovery_drill.py), Restricted runtime privileges (ops/runtime-privileges.sql), Staging installation with Docker Compose and Caddy, deploy/staging/up.sh one-command bring-up, Automated endpoint access review test, RateLimitFilter per-client rate limiting (+1 more)

### Community 136 - "Health Probes"
Cohesion: 0.20
Nodes (10): health, liveness, readiness, group, probes, show-components, show-details, include (+2 more)

### Community 137 - "Bank Duties UI"
Cohesion: 0.38
Nodes (8): createBankDutiesUi(), action(), render(), DUTIES, dutyLists(), LEVELS, words(), fixture()

### Community 138 - "Optimistic Versioning Docs"
Cohesion: 0.25
Nodes (7): expectedVersion optimistic concurrency (STALE_VERSION), STALE approval on changed target version, CHART_ACCOUNT approval action, Module 05.1: Chart of Accounts, bank.ledger_account and append-only bank.ledger_event (bank V2), Module 05.2: Ledger Accounts, platform.ledger_registry / platform.ledger_delivery (control V7)

### Community 140 - "Loan Servicing Models"
Cohesion: 0.22
Nodes (7): Collection, Details, LoanServicingModels, Operation, IMPAIR, RESTRUCTURE, WRITE_OFF

### Community 141 - "Portal Config"
Cohesion: 0.22
Nodes (8): cbs, portal, logging, pattern, level, client-id, redirect-uri, scope

### Community 143 - "Staging Compose"
Cohesion: 0.25
Nodes (7): name, networks, cbs, services, volumes, caddy_config, caddy_data

### Community 144 - "Reporting Docs"
Cohesion: 0.25
Nodes (7): Balance sheet with comparative date, Management and financial reporting, Income statement with comparative period, Management dashboard (deposits, loan quality, cash, work waiting), Report formats (JSON, CSV, A4 PDF), CSV export with formula neutralization, Single REPEATABLE READ read-only report transaction

### Community 145 - "Approval Inbox Docs"
Cohesion: 0.29
Nodes (6): Idempotency-Key registration replay, platform.approval_request / append-only platform.approval_event (Flyway V4), Module 03: Approval Inbox, Bank BCP 47 languages and personal UI language preference, BANK_SETTINGS approval action / platform.bank_settings (Flyway V5), Module 04: currencies, languages and business calendars

### Community 146 - "Funding Docs"
Cohesion: 0.25
Nodes (7): Module 08.2 Funding, customer balances and accounting integration, Customer subledger / GL reconciliation, Module 08.3 account operations, 09.1 transfers, 13.1 statements, Posted/available/withdrawable balance rules, Customer statement view (repeatable-read snapshot), Operation kinds TRANSFER/WITHDRAWAL/FUNDING_REVERSAL/HOLD/RELEASE, Streaming CSV statement export

### Community 147 - "Receipt Recovery Docs"
Cohesion: 0.29
Nodes (6): Bank migration V25 (bank.period_close), Hot-query indexes (control V44, bank V26), Bank V24 / control V39 servicing chunk tables, Daily processing at bank scale (chunked servicing), Sealed business day (bank.require_open_business_day), Resumable servicing chunks (cbs.servicing.chunk-size)

### Community 148 - "Chart UI"
Cohesion: 0.43
Nodes (7): chartCategories, chartLabel(), createChartUi(), action(), categoryChanged(), render(), defaultBalance()

### Community 149 - "Compliance UI"
Cohesion: 0.32
Nodes (4): createComplianceUi(), bank, fixture(), row

### Community 150 - "Journal UI"
Cohesion: 0.54
Nodes (7): createJournalUi(), action(), addLine(), loadAccounts(), readLines(), updateTotals(), journalTotals()

### Community 151 - "Servicing UI"
Cohesion: 0.32
Nodes (5): createServicingUi(), action(), bank, data, fixture()

### Community 152 - "Teller Cash Docs"
Cohesion: 0.38
Nodes (7): Funding posting contract (debit asset, credit customer liability), Cash accounting RECEIVE/DISPATCH/TRANSFER/DEPOSIT/WITHDRAWAL, Cash exception cases (control V18), Cash locations (vaults and tills), CASH_OPERATION approval action, Shortage and overage adjustments (control V19, bank V10), Teller and cash custody

### Community 153 - "Alerts and Guard Docs"
Cohesion: 0.38
Nodes (5): ProductionGuard configuration check, Customer credit/debit alerts, NotificationSender interface, PDF statement (StatementPdfTest), Statements and customer alerts

### Community 154 - "Session Config"
Cohesion: 0.29
Nodes (7): administrator-mfa-age, bootstrap-admin-subject, idle-timeout, local-mfa-bypass-until, maximum-session-age, maximum-token-age, access

### Community 155 - "Datasource Config"
Cohesion: 0.29
Nodes (7): hikari, password, url, username, connection-timeout, maximum-pool-size, datasource

### Community 156 - "Error Config"
Cohesion: 0.29
Nodes (7): include-message, include-stacktrace, server, address, error, port, shutdown

### Community 157 - "Collections UI"
Cohesion: 0.43
Nodes (4): createCollectionsUi(), action(), history(), fixture()

### Community 158 - "Statement Import UI"
Cohesion: 0.38
Nodes (3): createStatementImportUi(), fields, fixture()

### Community 159 - "Compose Logging"
Cohesion: 0.40
Nodes (6): logging, logging, driver, options, max-file, max-size

### Community 161 - "OAuth Config"
Cohesion: 0.33
Nodes (6): security, allow-local-http, audience, enabled, issuer, jwk-set-uri

### Community 162 - "Actuator Config"
Cohesion: 0.33
Nodes (6): web, include, management, endpoint, endpoints, exposure

### Community 165 - "Compose Environment"
Cohesion: 0.40
Nodes (5): environment, CBS_BIND_ADDRESS, SPRING_CONFIG_IMPORT, SPRING_PROFILES_ACTIVE, TZ

### Community 173 - "Payment Roles"
Cohesion: 0.50
Nodes (4): Role, AUDITOR, RECONCILER, SUPERVISOR

### Community 175 - "Portal Index Page"
Cohesion: 0.50
Nodes (4): CBS administration portal index.html, Administration sidebar navigation (data-view buttons), Shared editor dialog form, Interface language selector (en/fr/sw)

## Ambiguous Edges - Review These
- `Release decision: not ready for production banking` → `Go-live progress estimate (~45-50% overall)`  [AMBIGUOUS]
  docs/go-live-plan-2026-11-13.md · relation: conceptually_related_to
- `Closure check API GET /accounts/{id}/closure` → `Administration sidebar navigation (data-view buttons)`  [AMBIGUOUS]
  src/main/resources/static/admin/index.html · relation: conceptually_related_to

## Knowledge Gaps
- **399 isolated node(s):** `com.cbs:cbs-platform`, `TELLER`, `BRANCH_MANAGER`, `AUDITOR`, `READ_BRANCH` (+394 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 1047 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **54 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Production release gates (NOT PRODUCTION READY)` connect `Deployment and Recovery Docs` to `Alerts and Guard Docs`, `M-Pesa Reconciliation Docs`, `Receipt Recovery Docs`, `Ops Python Jobs`?**
  _High betweenness centrality (0.210) - this node is a cross-community bridge._
- **What connects `com.cbs:cbs-platform`, `TELLER`, `BRANCH_MANAGER` to the rest of the system?**
  _399 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Teller Cash Store` be split into smaller, more focused modules?**
  _Cohesion score 0.056064690026954175 - nodes in this community are weakly interconnected._
- **Why does `Production deployment, backup and disaster recovery` connect `Deployment and Recovery Docs` to `Test Plan and Gates`?**
  _High betweenness centrality (0.131) - this node is a cross-community bridge._
- **Should `Shared Java Imports` be split into smaller, more focused modules?**
  _Cohesion score 0.10799438990182328 - nodes in this community are weakly interconnected._

### Low-confidence Hints
_AMBIGUOUS edges — the extractor was unsure. Verify before acting on these._

- **What is the exact relationship between `Release decision: not ready for production banking` and `Go-live progress estimate (~45-50% overall)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Closure check API GET /accounts/{id}/closure` and `Administration sidebar navigation (data-view buttons)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._