package com.cbs.approvals;

import static com.cbs.approvals.ApprovalModels.*;
import com.cbs.access.AccessModels.StaffUpdate;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.ChangeRequest;
import com.cbs.tenancy.TenantModels.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ApprovalService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final com.cbs.access.LendingAccess lending;
    private final com.cbs.contracts.LoanFeeService loanFees;
    private final com.cbs.contracts.LoanServicingService loans;
    private final com.cbs.credit.CreditService credit;
    private final com.cbs.contracts.TermServicingService termServicing;
    private final com.cbs.contracts.ContractService contracts;
    private final com.cbs.fx.FxService fx;
    private final com.cbs.fx.FxValuationService valuations;
    private final com.cbs.fx.FxTradeService fxTrades;
    private final com.cbs.payments.PaymentService payments;
    private final com.cbs.payments.PaymentCaseService paymentCases;
    private final com.cbs.payments.PaymentAccess paymentAccess;
    private final com.cbs.payments.StatementImportService statements;
    private final com.cbs.compliance.ComplianceService compliance;
    private final com.cbs.servicing.ServicingService servicing;
    private final com.cbs.cash.DenominationService denominations;
    private final com.cbs.cash.CashService cash;
    private final com.cbs.access.BranchOperations branchOperations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantService tenants;
    private final TenantRepository tenantRepository;
    private final AccessService access;
    private final com.cbs.reference.ReferenceDataService reference;
    private final com.cbs.accounting.ChartService chart;
    private final com.cbs.accounting.LedgerService ledger;
    private final com.cbs.accounting.JournalService journals;
    private final com.cbs.accounting.DayCloseService days;
    private final com.cbs.products.ProductService products;
    private final com.cbs.customers.CustomerService customers;
    private final com.cbs.accounts.AccountService accounts;
    private final com.cbs.accounts.FundingService funding;
    private final com.cbs.accounts.OperationService operations;
    public ApprovalService(com.cbs.access.LendingAccess lending,com.cbs.contracts.LoanFeeService loanFees,com.cbs.contracts.LoanServicingService loans,com.cbs.credit.CreditService credit,com.cbs.contracts.TermServicingService termServicing,com.cbs.fx.FxValuationService valuations,com.cbs.contracts.ContractService contracts,com.cbs.fx.FxService fx,com.cbs.fx.FxTradeService fxTrades,com.cbs.payments.PaymentCaseService paymentCases,com.cbs.payments.PaymentAccess paymentAccess,com.cbs.payments.StatementImportService statements,com.cbs.payments.PaymentService payments,com.cbs.compliance.ComplianceService compliance,com.cbs.servicing.ServicingService servicing,com.cbs.cash.DenominationService denominations,com.cbs.cash.CashService cash,com.cbs.access.BranchOperations branchOperations,JdbcTemplate jdbc, PlatformTransactionManager manager, TenantService tenants,
            TenantRepository tenantRepository, AccessService access,com.cbs.reference.ReferenceDataService reference,
            com.cbs.accounting.ChartService chart,com.cbs.accounting.LedgerService ledger,com.cbs.accounting.JournalService journals,com.cbs.accounting.DayCloseService days,com.cbs.products.ProductService products,com.cbs.customers.CustomerService customers,com.cbs.accounts.AccountService accounts,com.cbs.accounts.FundingService funding,com.cbs.accounts.OperationService operations) {this.lending=lending;this.loanFees=loanFees;this.loans=loans;this.credit=credit;this.termServicing=termServicing;this.valuations=valuations;this.contracts=contracts;this.fx=fx;this.fxTrades=fxTrades;this.paymentCases=paymentCases;this.paymentAccess=paymentAccess;this.statements=statements;this.payments=payments;this.compliance=compliance;this.servicing=servicing;this.denominations=denominations;this.cash=cash;this.branchOperations=branchOperations;this.operations=operations;this.funding=funding;
        this.accounts=accounts;
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.tenants=tenants;
        this.tenantRepository=tenantRepository; this.access=access;
        this.reference=reference;
        this.chart=chart;
        this.ledger=ledger;
        this.journals=journals;this.days=days;this.products=products;this.customers=customers;
    }

    public Request bank(Actor actor, UUID key, UUID id, Action action, ChangeRequest input) {
        if(action!=Action.BANK_SUSPEND && action!=Action.BANK_RESUME) throw error(400,"INVALID_REQUEST");
        return submit(actor,key,id,action,input.expectedVersion(),input.reason(),input);
    }
    public Request staff(Actor actor, UUID key, UUID id, StaffUpdate input) {
        return submit(actor,key,id,Action.STAFF_UPDATE,input.expectedVersion(),input.reason(),input);
    }
    public Request settings(Actor actor,UUID key,UUID bankId,com.cbs.reference.ReferenceModels.Change input) {
        return submit(actor,key,bankId,Action.BANK_SETTINGS,input.expectedVersion(),input.reason(),input);
    }
    public Request chart(Actor actor,UUID key,UUID bankId,com.cbs.accounting.ChartModels.Change input) {
        return submit(actor,key,bankId,Action.CHART_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request ledger(Actor actor,UUID key,UUID bankId,com.cbs.accounting.LedgerModels.Change input) {
        return submit(actor,key,bankId,Action.LEDGER_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request lendingGrant(Actor actor,UUID key,UUID bank,com.cbs.access.LendingAccess.Grant input){return submit(actor,key,bank,Action.LENDING_ACCESS,input.expectedVersion(),input.reason(),input);}
    public Request loanFee(Actor actor,UUID key,UUID bank,com.cbs.contracts.LoanFeeModels.Change input){return submit(actor,key,bank,Action.LOAN_FEE,input.expectedVersion(),input.reason(),input);}
    public Request loanServicing(Actor actor,UUID key,UUID bank,com.cbs.contracts.LoanServicingModels.Instruction input){return submit(actor,key,bank,Action.LOAN_SERVICING,input.expectedVersion(),input.reason(),input);}
    public Request creditPolicy(Actor actor,UUID key,UUID bank,com.cbs.credit.CreditModels.Policy input){return submit(actor,key,bank,Action.CREDIT_POLICY,input.expectedVersion(),input.reason(),input);}
    public Request creditReview(Actor actor,UUID key,UUID bank,com.cbs.credit.CreditModels.Review input){return submit(actor,key,bank,Action.CREDIT_REVIEW,input.expectedVersion(),input.reason(),input);}
    public Request creditRelease(Actor actor,UUID key,UUID bank,com.cbs.credit.CreditModels.Release input){return submit(actor,key,bank,Action.CREDIT_RELEASE,input.expectedVersion(),input.reason(),input);}
    public Request termServicing(Actor actor,UUID key,UUID bank,com.cbs.contracts.TermServicingModels.Instruction input){return submit(actor,key,bank,Action.TERM_SERVICING,input.expectedVersion(),input.reason(),input);}
    public Request contractCorrection(Actor actor,UUID key,UUID bank,com.cbs.contracts.ContractModels.Correction input){return submit(actor,key,bank,Action.CONTRACT_CORRECTION,input.expectedVersion(),input.reason(),input);}
    public Request contractOpen(Actor actor,UUID key,UUID bank,com.cbs.contracts.ContractModels.Open input){return submit(actor,key,bank,Action.CONTRACT_OPEN,input.expectedVersion(),input.reason(),input);}
    public Request contractEvent(Actor actor,UUID key,UUID bank,com.cbs.contracts.ContractModels.Event input){return submit(actor,key,bank,Action.CONTRACT_EVENT,input.expectedVersion(),input.reason(),input);}
    public Request fxBook(Actor actor,UUID key,UUID bank,com.cbs.fx.FxValuationModels.Policy input){return submit(actor,key,bank,Action.FX_BOOK,input.expectedVersion(),input.reason(),input);}
    public Request fxClosingRate(Actor actor,UUID key,UUID bank,com.cbs.fx.FxValuationModels.ClosingRate input){return submit(actor,key,bank,Action.FX_CLOSING_RATE,input.expectedVersion(),input.reason(),input);}
    public Request fxValuation(Actor actor,UUID key,UUID bank,com.cbs.fx.FxValuationModels.Run input){return submit(actor,key,bank,Action.FX_VALUATION,input.expectedVersion(),input.reason(),input);}
    public Request fxTrade(Actor actor,UUID key,UUID bank,com.cbs.fx.FxTradeModels.Trade input){return submit(actor,key,bank,Action.FX_TRADE,input.expectedVersion(),input.reason(),input);}
    public Request fxReversal(Actor actor,UUID key,UUID bank,com.cbs.fx.FxTradeModels.Reversal input){return submit(actor,key,bank,Action.FX_REVERSAL,input.expectedVersion(),input.reason(),input);}
    public Request fxRate(Actor actor,UUID key,UUID bank,com.cbs.fx.FxModels.Rate input){return submit(actor,key,bank,Action.FX_RATE,input.expectedVersion(),input.reason(),input);}
    public Request fxWithdraw(Actor actor,UUID key,UUID bank,com.cbs.fx.FxModels.Withdraw input){return submit(actor,key,bank,Action.FX_RATE_WITHDRAW,input.expectedVersion(),input.reason(),input);}
    public Request paymentCase(Actor actor,UUID key,UUID bank,com.cbs.payments.PaymentCaseModels.Change input){return submit(actor,key,bank,Action.PAYMENT_CASE,input.expectedVersion(),input.reason(),input);}
    public Request paymentGrant(Actor actor,UUID key,UUID bank,com.cbs.payments.PaymentAccess.Grant input){return submit(actor,key,bank,Action.PAYMENT_ACCESS,input.expectedVersion(),input.reason(),input);}
    private static boolean paymentScoped(Action a){return com.cbs.access.BankDuties.PAYMENT_SCOPED.contains(a);}
    public Request statementAction(Actor actor,UUID key,UUID bank,com.cbs.payments.StatementImportModels.Action input){return submit(actor,key,bank,Action.STATEMENT_ACTION,input.expectedVersion(),input.reason(),input);}
    public Request payment(Actor actor,UUID key,UUID bank,com.cbs.payments.PaymentModels.Order input){return submit(actor,key,bank,Action.PAYMENT_ORDER,input.expectedVersion(),input.reason(),input);}
    public Request paymentReconcile(Actor actor,UUID key,UUID bank,com.cbs.payments.PaymentModels.Reconcile input){return submit(actor,key,bank,Action.PAYMENT_RECONCILE,input.expectedVersion(),input.reason(),input);}
    public Request compliance(Actor actor,UUID key,UUID bank,com.cbs.compliance.ComplianceModels.Review input){return submit(actor,key,bank,Action.COMPLIANCE_REVIEW,input.expectedVersion(),input.reason(),input);}
    public Request servicingPolicy(Actor actor,UUID key,UUID bank,com.cbs.servicing.ServicingModels.Policy input){return submit(actor,key,bank,Action.SERVICING_POLICY,input.expectedVersion(),input.reason(),input);}
    public Request servicingRun(Actor actor,UUID key,UUID bank,com.cbs.servicing.ServicingModels.Run input){return submit(actor,key,bank,Action.SERVICING_RUN,input.expectedVersion(),input.reason(),input);}
    public Request denominations(Actor actor,UUID key,UUID bank,com.cbs.cash.DenominationService.Change input){return submit(actor,key,bank,Action.CASH_DENOMINATIONS,input.expectedVersion(),input.reason(),input);}
    public Request cash(Actor actor,UUID key,UUID bank,com.cbs.cash.CashModels.Change input){return submit(actor,key,bank,Action.CASH_OPERATION,input.expectedVersion(),input.reason(),input);}
    public Request assignment(Actor actor,UUID key,UUID bank,com.cbs.access.BranchOperations.Assignment input){return submit(actor,key,bank,Action.ACCOUNT_BRANCH,input.expectedVersion(),input.reason(),input);}
    public Request branchLimit(Actor actor,UUID key,UUID bank,com.cbs.access.BranchOperations.Limit input){return submit(actor,key,bank,Action.BRANCH_LIMIT,input.expectedVersion(),input.reason(),input);}
    private com.cbs.notifications.NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired void notifications(com.cbs.notifications.NotificationService notifications){this.notifications=notifications;}
    private com.cbs.mandates.MandateService mandates;
    @org.springframework.beans.factory.annotation.Autowired void mandates(com.cbs.mandates.MandateService mandates){this.mandates=mandates;}
    private com.cbs.accounting.PeriodCloseService periods;
    @org.springframework.beans.factory.annotation.Autowired void periods(com.cbs.accounting.PeriodCloseService periods){this.periods=periods;}
    private com.cbs.reporting.ReportDefinitionService reportDefinitions;
    @org.springframework.beans.factory.annotation.Autowired void reportDefinitions(com.cbs.reporting.ReportDefinitionService reportDefinitions){this.reportDefinitions=reportDefinitions;}
    private com.cbs.compliance.ScreeningService screening;private com.cbs.compliance.MonitoringService monitoring;private com.cbs.compliance.ComplianceDecisions complianceDecisions;
    @org.springframework.beans.factory.annotation.Autowired void complianceAutomation(com.cbs.compliance.ScreeningService screening,com.cbs.compliance.MonitoringService monitoring,com.cbs.compliance.ComplianceDecisions decisions){this.screening=screening;this.monitoring=monitoring;this.complianceDecisions=decisions;}
    private com.cbs.access.BankDuties duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(com.cbs.access.BankDuties duties){this.duties=duties;}
    public Request bankDuty(Actor actor,UUID key,UUID bankId,com.cbs.access.BankDuties.Grant input){return submit(actor,key,bankId,Action.BANK_DUTY,input.expectedVersion(),input.reason(),input);}
    public Request bankDutyPolicy(Actor actor,UUID key,UUID bankId,com.cbs.access.BankDuties.Policy input){return submit(actor,key,bankId,Action.BANK_DUTY_POLICY,input.expectedVersion(),input.reason(),input);}

    /** Bank duty for a new request on the platform-administrator path, or null when another model governs it. */
    private com.cbs.access.BankDuties.Duty submitDuty(Actor actor,Action action,UUID bank,Object input,boolean lendingScoped){
        if(lendingScoped) return null;
        if(paymentScoped(action)) return actor.platformAdmin()?com.cbs.access.BankDuties.Duty.PAYMENTS:null;
        if(action==Action.ACCOUNT_OPERATION){var change=(com.cbs.accounts.OperationModels.Change)input;
            if(change.kind()!=com.cbs.accounts.OperationModels.Kind.CLOSURE_SETTLEMENT&&branchOperations.branch(bank,change.accountId())!=null) return null;}
        if(action==Action.CASH_OPERATION&&((com.cbs.cash.CashModels.Change)input).kind()!=com.cbs.cash.CashModels.Kind.REGISTER) return null;
        return com.cbs.access.BankDuties.duty(action);
    }
    /** Same classification for a stored request; branch and cash requests use the path they were submitted on. */
    private com.cbs.access.BankDuties.Duty storedDuty(Request request){
        return request.bankId()==null?null:duties.storedDuty(request.id());
    }
    /** Duty the deciding or reading administrator needs; payment requests only on the administrator path. */
    private com.cbs.access.BankDuties.Duty reviewDuty(Actor actor,Request request){
        if(request.bankId()!=null&&paymentScoped(request.action())) return actor.platformAdmin()?com.cbs.access.BankDuties.Duty.PAYMENTS:null;
        return storedDuty(request);
    }
    private com.cbs.access.BankDuties.Duty makerDuty(Request request){
        return request.bankId()==null?null:duties.makerDuty(request.id());
    }
    /**
     * Break-glass: if duties are enforced but fewer than two access administrators remain, platform
     * administrators may still submit and independently approve switching enforcement off.
     */
    private boolean dutyRecovery(Action action,UUID bank,Object input){
        return action==Action.BANK_DUTY_POLICY&&!((com.cbs.access.BankDuties.Policy)input).enforced()&&duties.lockedOut(bank);
    }
    private boolean dutyRecovery(Request request){
        return request.action()==Action.BANK_DUTY_POLICY&&!Boolean.TRUE.equals(request.proposedValues().get("enforced"))&&duties.lockedOut(request.bankId());
    }

    public Request screeningList(Actor actor,UUID key,UUID bankId,com.cbs.compliance.AutomationModels.Activation input){return submit(actor,key,bankId,Action.SCREENING_LIST,input.expectedVersion(),input.reason(),input);}
    public Request monitoringPolicy(Actor actor,UUID key,UUID bankId,com.cbs.compliance.AutomationModels.Policy input){return submit(actor,key,bankId,Action.MONITORING_POLICY,input.expectedVersion(),input.reason(),input);}
    public Request complianceDismiss(Actor actor,UUID key,UUID bankId,com.cbs.compliance.AutomationModels.Dismiss input){return submit(actor,key,bankId,Action.COMPLIANCE_ALERT_DISMISS,input.expectedVersion(),input.reason(),input);}
    public Request reportDefinition(Actor actor,UUID key,UUID bankId,com.cbs.reporting.ReportModels.Definition input){return submit(actor,key,bankId,Action.REPORT_DEFINITION,input.expectedVersion(),input.reason(),input);}
    public Request periodClosePolicy(Actor actor,UUID key,UUID bankId,com.cbs.accounting.PeriodCloseModels.Policy input){return submit(actor,key,bankId,Action.PERIOD_CLOSE_POLICY,input.expectedVersion(),input.reason(),input);}
    public Request periodClose(Actor actor,UUID key,UUID bankId,com.cbs.accounting.PeriodCloseModels.Run input){return submit(actor,key,bankId,Action.PERIOD_CLOSE,input.expectedVersion(),input.reason(),input);}
    public Request customerParties(Actor actor,UUID key,UUID bankId,com.cbs.mandates.MandateModels.Parties input){return submit(actor,key,bankId,Action.CUSTOMER_PARTIES,input.expectedVersion(),input.reason(),input);}
    public Request accountMandate(Actor actor,UUID key,UUID bankId,com.cbs.mandates.MandateModels.Mandate input){return submit(actor,key,bankId,Action.ACCOUNT_MANDATE,input.expectedVersion(),input.reason(),input);}
    public Request notificationPreference(Actor actor,UUID key,UUID bankId,com.cbs.notifications.NotificationModels.Preference input){return submit(actor,key,bankId,Action.NOTIFICATION_PREFERENCE,input.expectedVersion(),input.reason(),input);}
    public Request limits(Actor actor,UUID key,UUID bankId,com.cbs.access.TransactionLimits.Change input){return submit(actor,key,bankId,Action.TRANSACTION_LIMIT,input.expectedVersion(),input.reason(),input);}
    public Request operation(Actor actor,UUID key,UUID bankId,com.cbs.accounts.OperationModels.Change input){return submit(actor,key,bankId,Action.ACCOUNT_OPERATION,input.expectedVersion(),input.reason(),input);}
    public Request branchOperation(Actor actor,UUID key,UUID bankId,UUID branchId,com.cbs.accounts.OperationModels.Change input){
        return tx.execute(t->{
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            tenantRepository.get(bankId,true);
            if(!branchId.equals(branchOperations.branch(bankId,input.accountId())))throw error(403,"BRANCH_ACCESS_DENIED");
            return operation(actor,key,bankId,input);
        });
    }
    public Request funding(Actor actor,UUID key,UUID bankId,com.cbs.accounts.FundingModels.Change input){return submit(actor,key,bankId,Action.ACCOUNT_FUNDING,input.expectedVersion(),input.reason(),input);}
    public Request account(Actor actor,UUID key,UUID bankId,com.cbs.accounts.AccountModels.Change input){
        return submit(actor,key,bankId,Action.DEPOSIT_ACCOUNT,input.expectedVersion(),input.reason(),input);
    }
    public Request customer(Actor actor,UUID key,UUID bankId,com.cbs.customers.CustomerModels.Change input){
        return submit(actor,key,bankId,Action.CUSTOMER_CHANGE,input.expectedVersion(),input.reason(),input);
    }
    public Request product(Actor actor,UUID key,UUID bankId,com.cbs.products.ProductModels.Change input) {
        return submit(actor,key,bankId,Action.DEPOSIT_PRODUCT,input.expectedVersion(),input.reason(),input);
    }
    public Request closeDay(Actor actor,UUID key,UUID bankId,com.cbs.accounting.DayCloseModels.Change input) {
        return submit(actor,key,bankId,Action.DAY_CLOSE,input.expectedVersion(),input.reason(),input);
    }
    public Request journal(Actor actor,UUID key,UUID bankId,com.cbs.accounting.JournalModels.Change input) {
        return submit(actor,key,bankId,Action.JOURNAL_POST,input.expectedVersion(),input.reason(),input);
    }
    public Request reverseJournal(Actor actor,UUID key,UUID bankId,UUID original,com.cbs.accounting.JournalModels.Reversal input) {
        AccessService.admin(actor);
        return tx.execute(t->{
            access.requireAssignable(actor.issuer(),actor.subject());
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            var existing=jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=? AND maker_subject=? AND idempotency_key=?",(r,n)->request(r),actor.issuer(),actor.subject(),key);
            if(!existing.isEmpty()) {
                Request saved=existing.getFirst();
                if(saved.action()!=Action.JOURNAL_POST||!saved.targetId().equals(bankId))throw error(409,"IDEMPOTENCY_CONFLICT");
                var previous=JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,saved.id()),com.cbs.accounting.JournalModels.Change.class);
                if(!original.equals(previous.reversalOf())||input.expectedVersion()!=previous.expectedVersion()||!input.reference().equals(previous.reference())||!input.reason().equals(previous.reason()))throw error(409,"IDEMPOTENCY_CONFLICT");
                return saved;
            }
            tenantRepository.get(bankId,true);
            return journal(actor,key,bankId,journals.reversal(actor,bankId,original,input));
        });
    }
    private Request submit(Actor actor, UUID key, UUID id, Action action, long version, String reason, Object input) {
        boolean scopedLoan=lending.scoped(action,id,input);
        if(!scopedLoan&&paymentScoped(action))paymentAccess.require(actor,id,com.cbs.payments.PaymentAccess.Capability.MAKE);else if(!scopedLoan&&action!=Action.ACCOUNT_OPERATION&&action!=Action.CASH_OPERATION)AccessService.admin(actor);
        return tx.execute(transaction->{
            access.requireAssignable(actor.issuer(),actor.subject());
            if(scopedLoan){tenantRepository.get(id,true);lending.maker(actor,id,lending.scope(id,action,input));}
            var duty=submitDuty(actor,action,id,input,scopedLoan);
            if(duty!=null&&!dutyRecovery(action,id,input))duties.require(actor,id,duty,false);
            // Serialize retries before checking the target, including retries after the request was approved.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},actor.issuer()+":"+actor.subject()+":"+key);
            var existing=jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=? AND maker_subject=? AND idempotency_key=?",
                    (row,n)->request(row),actor.issuer(),actor.subject(),key);
            String payload=JSON.writeValueAsString(input);
            if(!existing.isEmpty()) {
                Request saved=existing.getFirst();
                Boolean same=jdbc.queryForObject("SELECT payload=?::jsonb FROM platform.approval_request WHERE id=?",Boolean.class,payload,saved.id());
                if(saved.action()!=action || !saved.targetId().equals(id) || !Boolean.TRUE.equals(same)) throw error(409,"IDEMPOTENCY_CONFLICT");
                if(action==Action.ACCOUNT_OPERATION)branchOperations.readRequest(actor,saved.id());
                if(action==Action.CASH_OPERATION)cash.readRequest(actor,saved.id(),false,false);
                return saved;
            }
            if(paymentScoped(action)){tenantRepository.get(id,true);paymentAccess.require(actor,id,com.cbs.payments.PaymentAccess.Capability.MAKE);}
            Map<String,Object> before,after;
            String label;
            UUID bankId=null;
            if(action==Action.BANK_DUTY){var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BankDuties.Grant)input;duties.validateGrant(actor,bank,change);before=duties.before(id,change.staffId());after=Map.of("grant",change);label=bank.displayName()+" — bank duties";bankId=id;
            } else if(action==Action.BANK_DUTY_POLICY){var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BankDuties.Policy)input;duties.validatePolicy(actor,bank,change);before=Map.of("enforced",duties.enforced(id));after=Map.of("enforced",change.enforced());label=bank.displayName()+" — duty segregation";bankId=id;
            } else if(action==Action.LENDING_ACCESS){var bank=tenantRepository.get(id,true);lending.validateGrant(actor,bank,(com.cbs.access.LendingAccess.Grant)input);before=Map.of();after=Map.of("grant",input);label=bank.displayName();bankId=id;
            } else if(action==Action.LOAN_FEE){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(loanFees.validate(bank,(com.cbs.contracts.LoanFeeModels.Change)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.LOAN_SERVICING){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(loans.validate(bank,(com.cbs.contracts.LoanServicingModels.Instruction)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.CREDIT_POLICY){var bank=tenantRepository.get(id,true);credit.validatePolicy(actor,bank,(com.cbs.credit.CreditModels.Policy)input);before=Map.of();after=Map.of("policy",input);label=bank.displayName();bankId=id;
            } else if(action==Action.CREDIT_REVIEW){var bank=tenantRepository.get(id,true);before=Map.of();after=credit.validateReview(actor,bank,(com.cbs.credit.CreditModels.Review)input,false);label=bank.displayName();bankId=id;
            } else if(action==Action.CREDIT_RELEASE){var bank=tenantRepository.get(id,true);before=Map.of();after=credit.validateRelease(actor,bank,(com.cbs.credit.CreditModels.Release)input,false);label=bank.displayName();bankId=id;
            } else if(action==Action.TERM_SERVICING){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(termServicing.validate(bank,(com.cbs.contracts.TermServicingModels.Instruction)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.CONTRACT_CORRECTION){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(contracts.validateCorrection(bank,(com.cbs.contracts.ContractModels.Correction)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.CONTRACT_OPEN){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(contracts.validateOpen(bank,(com.cbs.contracts.ContractModels.Open)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.CONTRACT_EVENT){var bank=tenantRepository.get(id,true);before=Map.of();after=contracts.values(contracts.validateEvent(bank,(com.cbs.contracts.ContractModels.Event)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.FX_BOOK){var bank=tenantRepository.get(id,true);before=Map.of();after=Map.of("book",valuations.validatePolicy(bank,(com.cbs.fx.FxValuationModels.Policy)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.FX_CLOSING_RATE){var bank=tenantRepository.get(id,true);valuations.validateRate(bank,(com.cbs.fx.FxValuationModels.ClosingRate)input);before=Map.of();after=Map.of("closingRate",input);label=bank.displayName();bankId=id;
            } else if(action==Action.FX_VALUATION){var bank=tenantRepository.get(id,true);before=Map.of();after=Map.of("valuation",valuations.validateRun(bank,(com.cbs.fx.FxValuationModels.Run)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.FX_TRADE){var bank=tenantRepository.get(id,true);before=Map.of();after=fxTrades.values(fxTrades.validate(bank,(com.cbs.fx.FxTradeModels.Trade)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.FX_REVERSAL){var bank=tenantRepository.get(id,true);before=Map.of();after=fxTrades.values(fxTrades.validateReversal(bank,(com.cbs.fx.FxTradeModels.Reversal)input,UUID.randomUUID()));label=bank.displayName();bankId=id;
            } else if(action==Action.FX_RATE){var bank=tenantRepository.get(id,true);var change=(com.cbs.fx.FxModels.Rate)input;fx.validate(bank,change);before=Map.of();after=fx.values(change);label=bank.displayName();bankId=id;
            } else if(action==Action.FX_RATE_WITHDRAW){var bank=tenantRepository.get(id,true);var change=(com.cbs.fx.FxModels.Withdraw)input;fx.validateWithdrawal(bank,change);before=fx.values(fx.rate(id,change.rateId()));after=Map.of("withdrawal",change);label=bank.displayName();bankId=id;
            } else if(action==Action.PAYMENT_ACCESS){
                var bank=tenantRepository.get(id,true);var change=(com.cbs.payments.PaymentAccess.Grant)input;paymentAccess.validate(actor,bank,change);before=Map.of();after=Map.of("grant",change);label=bank.displayName();bankId=id;
            } else if(action==Action.PAYMENT_CASE){
                var bank=tenantRepository.get(id,true);var change=(com.cbs.payments.PaymentCaseModels.Change)input;before=Map.of();after=paymentCases.values(actor,bank,change);label=bank.displayName();bankId=id;
            } else if(action==Action.STATEMENT_ACTION){
                var bank=tenantRepository.get(id,true);var change=(com.cbs.payments.StatementImportModels.Action)input;before=Map.of();after=statements.values(actor,bank,change);label=bank.displayName();bankId=id;
            } else if(action==Action.PAYMENT_ORDER){
                var bank=tenantRepository.get(id,true);var change=(com.cbs.payments.PaymentModels.Order)input;payments.validate(actor,bank,change);before=Map.of();after=payments.values(bank,change);label=bank.displayName();bankId=id;
            } else if(action==Action.PAYMENT_RECONCILE){
                var bank=tenantRepository.get(id,true);var change=(com.cbs.payments.PaymentModels.Reconcile)input;before=Map.of();after=payments.values(actor,bank,change);label=bank.displayName();bankId=id;
            } else if(action==Action.STAFF_UPDATE) {
                var staff=access.staff(id,true);
                if(!staff.issuer().equals(actor.issuer())) throw error(404,"STAFF_NOT_FOUND");
                if(staff.version()!=version) throw error(409,"STALE_VERSION");
                StaffUpdate update=(StaffUpdate)input;
                if(!update.active() && staff.subject().equals(actor.subject())) throw error(409,"SELF_DEACTIVATION_FORBIDDEN");
                before=staffValues(staff.displayName(),staff.email(),staff.active());
                after=staffValues(update.displayName(),update.email(),update.active());
                label=staff.displayName();
            } else if(action==Action.COMPLIANCE_REVIEW) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.compliance.ComplianceModels.Review)input;compliance.validate(bank,change);before=compliance.before(actor,id,change);after=compliance.values(change);label="Compliance case review";bankId=id;
            } else if(action==Action.SERVICING_POLICY) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.servicing.ServicingModels.Policy)input;servicing.validatePolicy(bank,change);before=Map.of();after=servicing.policyValues(change);label="Deposit servicing policy";bankId=id;
            } else if(action==Action.SERVICING_RUN) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.servicing.ServicingModels.Run)input;var batch=servicing.prepare(bank,change,UUID.randomUUID());before=Map.of();after=servicing.values(batch);label="Daily interest, fees and taxes";bankId=id;
            } else if(action==Action.CASH_DENOMINATIONS) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.cash.DenominationService.Change)input;denominations.validate(bank,change);before=Map.of("catalogue",denominations.list(id));after=denominations.values(change);label="Cash denomination catalogue";bankId=id;
            } else if(action==Action.CASH_OPERATION) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.cash.CashModels.Change)input;var entry=cash.validate(actor,bank,change,UUID.randomUUID());before=cash.before(bank,entry);after=cash.values(entry);label=entry.location().code()+" — "+change.kind();bankId=id;
            } else if(action==Action.ACCOUNT_BRANCH) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BranchOperations.Assignment)input;branchOperations.validate(bank,change);
                var old=branchOperations.branch(id,change.accountId());before=old==null?Map.of():Map.of("branchId",old);after=branchOperations.values(change);label="Account branch assignment";bankId=id;
            } else if(action==Action.BRANCH_LIMIT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.BranchOperations.Limit)input;branchOperations.validate(bank,change);
                before=branchOperations.before(id,change);after=branchOperations.values(change);label="Branch transaction limit";bankId=id;
            } else if(action==Action.SCREENING_LIST) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.compliance.AutomationModels.Activation)input;screening.validate(bank,change);
                before=screening.before(bank,change);after=screening.values(bank,change);label="Screening list "+after.get("name");bankId=id;
            } else if(action==Action.MONITORING_POLICY) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.compliance.AutomationModels.Policy)input;monitoring.validate(bank,change);
                before=monitoring.before(bank,change.currency());after=monitoring.values(change);label="Transaction monitoring "+change.currency();bankId=id;
            } else if(action==Action.COMPLIANCE_ALERT_DISMISS) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.compliance.AutomationModels.Dismiss)input;complianceDecisions.validate(bank,change);
                before=complianceDecisions.before(bank,change);after=complianceDecisions.values(change);label=(change.type()==com.cbs.compliance.AutomationModels.ItemType.SCREENING_HIT?"Dismiss screening match":"Dismiss monitoring alert");bankId=id;
            } else if(action==Action.REPORT_DEFINITION) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.reporting.ReportModels.Definition)input;reportDefinitions.validate(bank,change);
                before=reportDefinitions.before(bank,change.code());after=reportDefinitions.values(bank,change);label="Report definition "+change.code();bankId=id;
            } else if(action==Action.PERIOD_CLOSE_POLICY) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounting.PeriodCloseModels.Policy)input;periods.validate(bank,change);
                before=periods.policyBefore(id);after=periods.values(change);label="Month-end and year-end close policy";bankId=id;
            } else if(action==Action.PERIOD_CLOSE) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounting.PeriodCloseModels.Run)input;var plan=periods.prepare(bank,change,UUID.randomUUID());
                before=Map.of();after=periods.values(plan);label=(plan.type()==com.cbs.accounting.PeriodCloseModels.Type.YEAR?"Year-end close ":"Month-end close ")+plan.periodEnd();bankId=id;
            } else if(action==Action.CUSTOMER_PARTIES) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.mandates.MandateModels.Parties)input;mandates.validate(bank,change);
                before=mandates.partiesBefore(id,change.organizationId());after=mandates.values(bank,change);label="Organization directors and owners";bankId=id;
            } else if(action==Action.ACCOUNT_MANDATE) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.mandates.MandateModels.Mandate)input;mandates.validate(bank,change);
                before=mandates.mandateBefore(id,change.accountId());after=mandates.values(bank,change);label="Account mandate and signing rule";bankId=id;
            } else if(action==Action.NOTIFICATION_PREFERENCE) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.notifications.NotificationModels.Preference)input;notifications.validate(bank,change);
                before=notifications.before(id,change.customerId());after=notifications.values(change);label="Customer alert preference";bankId=id;
            } else if(action==Action.TRANSACTION_LIMIT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.access.TransactionLimits.Change)input;
                com.cbs.access.TransactionLimits.validate(bank,change);before=com.cbs.access.TransactionLimits.current(jdbc,id,change.currency(),change.operation());after=com.cbs.access.TransactionLimits.values(change);label=bank.displayName()+" — transaction limits";bankId=id;
            } else if(action==Action.ACCOUNT_OPERATION) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.OperationModels.Change)input;
                branchOperations.authorize(actor,id,change,false);var entry=operations.validate(bank,change,UUID.randomUUID());before=Map.of();after=operations.values(bank,change);label=entry.account().accountNumber()+" — "+change.kind();bankId=id;
            } else if(action==Action.ACCOUNT_FUNDING) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.FundingModels.Change)input;
                var account=funding.validate(bank,change);before=Map.of();after=funding.values(id,change);label=account.accountNumber()+" — "+change.amount()+" "+account.terms().currency();bankId=id;
            } else if(action==Action.DEPOSIT_ACCOUNT) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.accounts.AccountModels.Change)input;
                accounts.validate(bank,change);before=accounts.before(id,change);after=accounts.values(id,change);
                label=change.accountId()==null?"New deposit account": "Deposit account "+change.accountId();bankId=id;
            } else if(action==Action.CUSTOMER_CHANGE) {
                var bank=tenantRepository.get(id,true);var change=(com.cbs.customers.CustomerModels.Change)input;
                customers.validate(bank,change);before=customers.before(id,change);after=customers.values(change);
                label=change.customerId()==null?"New individual customer":"Customer "+change.customerId();bankId=id;
            } else if(action==Action.DEPOSIT_PRODUCT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.products.ProductModels.Change)input;
                products.validate(bank,change);before=products.before(id,change);after=products.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.DAY_CLOSE) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.DayCloseModels.Change)input;
                days.validate(bank,change,null);before=Map.of("businessDate",change.businessDate().toString());after=days.values(change);
                label=bank.displayName()+" — "+change.businessDate();bankId=id;
            } else if(action==Action.JOURNAL_POST) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.JournalModels.Change)input;
                journals.validate(bank,change);
                before=Map.of();after=journals.values(id,change);
                label=change.reference().trim()+" — "+change.currency();bankId=id;
            } else if(action==Action.LEDGER_ACCOUNT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.LedgerModels.Change)input;
                ledger.validate(bank,change);
                before=ledger.before(id,change);after=ledger.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.CHART_ACCOUNT) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.accounting.ChartModels.Change)input;
                chart.validate(bank,change);
                before=chart.before(id,change);after=chart.values(id,change);
                label=change.code()+" — "+change.name().trim();bankId=id;
            } else if(action==Action.BANK_SETTINGS) {
                var bank=tenantRepository.get(id,true);
                var change=(com.cbs.reference.ReferenceModels.Change)input;
                reference.validate(bank,change);
                before=reference.values(reference.read(bank));after=reference.values(change);
                label=bank.displayName();bankId=id;
            } else {
                var bank=tenantRepository.get(id,true);
                if(bank.version()!=version) throw error(409,"STALE_VERSION");
                String from=action==Action.BANK_SUSPEND?"ACTIVE":"SUSPENDED";
                if(!bank.status().name().equals(from)) throw error(409,"INVALID_TRANSITION");
                before=Map.of("status",from);
                after=Map.of("status",action==Action.BANK_SUSPEND?"SUSPENDED":"ACTIVE");
                label=bank.displayName(); bankId=id;
            }
            UUID requestId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform.approval_request
                    (id,issuer,maker_subject,idempotency_key,action,target_id,bank_id,target_label,expected_version,reason,payload,before_values,proposed_values)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                    """,requestId,actor.issuer(),actor.subject(),key,action.name(),id,bankId,label,version,reason,payload,
                    JSON.writeValueAsString(before),JSON.writeValueAsString(after));
            if(scopedLoan)lending.record(requestId,id,lending.scope(id,action,input));
            if(paymentScoped(action))jdbc.update("INSERT INTO platform.payment_approval_scope VALUES (?,?,?)",requestId,id,actor.platformAdmin());
            if(action==Action.PAYMENT_ORDER)payments.record(actor,id,(com.cbs.payments.PaymentModels.Order)input,requestId);
            if(action==Action.PAYMENT_RECONCILE)payments.recordResolution(actor,tenantRepository.get(id,false),(com.cbs.payments.PaymentModels.Reconcile)input,requestId);
            if(action==Action.ACCOUNT_OPERATION)branchOperations.record(actor,id,(com.cbs.accounts.OperationModels.Change)input,requestId);
            if(action==Action.CASH_OPERATION)cash.record(actor,id,(com.cbs.cash.CashModels.Change)input,requestId);
            audit(actor,requestId,"SUBMITTED",reason);
            return get(actor,requestId,false);
        });
    }

    public Page<Request> list(Actor actor, Scope scope, Action action, UUID bankId, int limit, int offset) {
        AccessService.admin(actor); AccessService.page(limit,offset);
        String condition=switch(scope) {
            case INBOX -> " AND status='PENDING'";
            case MINE -> " AND maker_subject=?";
            case HISTORY -> " AND status<>'PENDING'";
        };
        List<Object> args=new ArrayList<>(); args.add(actor.issuer());
        if(scope==Scope.MINE) args.add(actor.subject());
        if(action!=null) { condition+=" AND action=?"; args.add(action.name()); }
        if(bankId!=null) { condition+=" AND bank_id=?"; args.add(bankId); }
        condition+=" AND (NOT EXISTS(SELECT 1 FROM platform.lending_approval_scope ls WHERE ls.request_id=platform.approval_request.id) OR EXISTS(SELECT 1 FROM platform.lending_approval_scope ls JOIN platform.lending_access g ON g.tenant_id=ls.tenant_id JOIN platform.staff_identity si ON si.id=g.staff_id WHERE ls.request_id=platform.approval_request.id AND si.issuer=? AND si.subject=? AND si.active AND g.active AND ((g.payload->>'bankWide')::boolean OR EXISTS(SELECT 1 FROM platform.account_branch ab JOIN platform.branch b ON b.id=ab.branch_id AND b.active WHERE ab.account_id=ls.account_id AND ab.tenant_id=ls.tenant_id AND jsonb_exists(g.payload->'branches',ab.branch_id::text)))))";
        args.add(actor.issuer());args.add(actor.subject());
        // In banks that enforce duties, list only requests whose duty the reader holds (or audits), and their own.
        String duty=com.cbs.access.BankDuties.dutySql("platform.approval_request.action");
        // Requests to switch enforcement off stay visible: they are the documented lockout recovery path.
        condition+=" AND (bank_id IS NULL OR maker_subject=? OR "+duty+" IS NULL OR (action='BANK_DUTY_POLICY' AND payload->>'enforced'='false')"
                +" OR NOT EXISTS(SELECT 1 FROM platform.bank_duty_policy dp WHERE dp.tenant_id=platform.approval_request.bank_id AND dp.enforced)"
                +" OR EXISTS(SELECT 1 FROM platform.lending_approval_scope dls WHERE dls.request_id=platform.approval_request.id)"
                +" OR EXISTS(SELECT 1 FROM platform.bank_duty dg JOIN platform.staff_identity ds ON ds.id=dg.staff_id WHERE dg.tenant_id=platform.approval_request.bank_id"
                +" AND ds.issuer=? AND ds.subject=? AND ds.active AND dg.active"
                +" AND (dg.payload->>'expiresAt' IS NULL OR (dg.payload->>'expiresAt')::timestamptz>clock_timestamp())"
                +" AND ((dg.payload->>'audit')::boolean OR jsonb_exists(dg.payload->'submit',"+duty+") OR jsonb_exists(dg.payload->'approve',"+duty+"))))";
        args.add(actor.subject());args.add(actor.issuer());args.add(actor.subject());
        args.add(limit); args.add(offset);
        return new Page<>(jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=?"+condition+
                " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(row,n)->request(row),args.toArray()),limit,offset);
    }
    public Page<Request> lendingInbox(Actor actor,UUID bank,int limit,int offset){AccessService.page(limit,offset);return tx.execute(t->{tenantRepository.get(bank,true);String filter=lending.filter(actor,bank,"s.account_id");return new Page<>(jdbc.query("SELECT a.* FROM platform.approval_request a JOIN platform.lending_approval_scope s ON s.request_id=a.id WHERE a.issuer=? AND a.bank_id=?"+filter+" ORDER BY (a.status='PENDING') DESC,a.created_at DESC,a.id LIMIT ? OFFSET ?",(r,n)->request(r),actor.issuer(),bank,limit,offset),limit,offset);});}
    public void lendingRequestBank(Actor actor,UUID bank,UUID id){lending.read(actor,bank,null);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.lending_approval_scope WHERE request_id=? AND tenant_id=?)",Boolean.class,id,bank))throw error(404,"APPROVAL_NOT_FOUND");lending.readRequest(actor,id);}
    public Page<Request> paymentInbox(Actor actor,UUID bank,int limit,int offset){paymentAccess.require(actor,bank,com.cbs.payments.PaymentAccess.Capability.READ);AccessService.page(limit,offset);return new Page<>(jdbc.query("SELECT * FROM platform.approval_request WHERE issuer=? AND bank_id=? AND action IN ('PAYMENT_CASE','STATEMENT_ACTION','PAYMENT_RECONCILE') ORDER BY (status='PENDING') DESC,created_at DESC,id LIMIT ? OFFSET ?",(r,n)->request(r),actor.issuer(),bank,limit,offset),limit,offset);}
    public void paymentRequestBank(Actor actor,UUID bank,UUID id){paymentAccess.require(actor,bank,com.cbs.payments.PaymentAccess.Capability.READ);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.approval_request WHERE id=? AND issuer=? AND bank_id=? AND action IN ('PAYMENT_CASE','STATEMENT_ACTION','PAYMENT_RECONCILE'))",Boolean.class,id,actor.issuer(),bank))throw error(404,"APPROVAL_NOT_FOUND");}
    public Details details(Actor actor, UUID id) {
        Request request=get(actor,id,false);
        return new Details(request,jdbc.query("SELECT * FROM platform.approval_event WHERE request_id=? ORDER BY occurred_at,id",
                (row,n)->new Event(row.getObject("id",UUID.class),row.getString("actor_subject"),row.getString("action"),
                        row.getString("reason"),row.getObject("correlation_id",UUID.class),row.getTimestamp("occurred_at").toInstant()),id));
    }

    public Request decide(Actor actor, UUID id, Decision decision, String reason) {
        return tx.execute(transaction->{
            Request request=get(actor,id,true);
            var lendingScope=lending.request(id);
            if(lendingScope!=null){tenantRepository.get(request.bankId(),true);if(decision==Decision.CANCEL)lending.maker(actor,request.bankId(),lendingScope);else lending.role(actor,request.bankId(),lendingScope.accountId(),com.cbs.access.LendingAccess.Role.CREDIT_APPROVER);}
            else if(paymentScoped(request.action()))paymentAccess.require(actor,request.bankId(),decision==Decision.CANCEL?com.cbs.payments.PaymentAccess.Capability.MAKE:com.cbs.payments.PaymentAccess.Capability.APPROVE);else if(request.action()==Action.ACCOUNT_OPERATION)branchOperations.review(actor,id,decision==Decision.CANCEL);else if(request.action()==Action.CASH_OPERATION)cash.readRequest(actor,id,true,decision==Decision.CANCEL);else AccessService.admin(actor);
            // A maker may always withdraw their own request; deciding needs the approve duty.
            if(decision!=Decision.CANCEL){var duty=reviewDuty(actor,request);if(duty!=null&&!dutyRecovery(request))duties.require(actor,request.bankId(),duty,true);}
            boolean own=request.makerSubject().equals(actor.subject());
            if(decision==Decision.CANCEL && !own) throw error(403,"ONLY_MAKER_CAN_CANCEL");
            if(decision!=Decision.CANCEL && own) throw error(403,"SELF_APPROVAL_FORBIDDEN");
            Status next=switch(decision) { case APPROVE -> Status.APPROVED; case REJECT -> Status.REJECTED; case CANCEL -> Status.CANCELLED; };
            if(request.status()!=Status.PENDING) {
                if(request.reviewerSubject().equals(actor.subject()) && (request.status()==next || (decision==Decision.APPROVE && request.status()==Status.STALE))) return request;
                throw error(409,"APPROVAL_ALREADY_DECIDED");
            }
            access.requireAssignable(actor.issuer(),actor.subject());
            if(decision==Decision.APPROVE) {
                // Keep the maker active until the change and its decision have committed.
                var maker=jdbc.queryForObject("SELECT active FROM platform.staff_identity WHERE issuer=? AND subject=? FOR SHARE",
                        Boolean.class,actor.issuer(),request.makerSubject());
                if(!Boolean.TRUE.equals(maker)) throw error(409,"MAKER_DISABLED");
                if(paymentScoped(request.action())){var privileged=jdbc.queryForList("SELECT maker_admin FROM platform.payment_approval_scope WHERE request_id=?",Boolean.class,id);paymentAccess.require(new Actor(actor.issuer(),request.makerSubject(),!privileged.isEmpty()&&Boolean.TRUE.equals(privileged.getFirst()),actor.correlationId()),request.bankId(),com.cbs.payments.PaymentAccess.Capability.MAKE);}
                long version;
                if(request.action()==Action.STAFF_UPDATE) {
                    var staff=access.staff(request.targetId(),true);
                    if(!staff.issuer().equals(actor.issuer())) throw error(404,"STAFF_NOT_FOUND");
                    version=staff.version();
                } else version=tenantRepository.get(request.targetId(),true).version();
                if(version!=request.expectedVersion()) next=Status.STALE;
                else {
                    if(lendingScope!=null){lending.maker(new Actor(actor.issuer(),request.makerSubject(),false,actor.correlationId()),request.bankId(),lendingScope);lending.checker(actor,request.bankId(),lendingScope,id,true);}
                    var makerDuty=makerDuty(request);
                    if(makerDuty!=null&&!dutyRecovery(request))duties.requireMaker(actor.issuer(),request.makerSubject(),request.bankId(),makerDuty);
                    String auditReason="Approved request "+request.id();
                    ChangeRequest change=new ChangeRequest(request.expectedVersion(),auditReason);
                    switch(request.action()) {
                        case BANK_SUSPEND -> tenants.suspend(actor,request.targetId(),change);
                        case BANK_RESUME -> tenants.resume(actor,request.targetId(),change);
                        case BANK_DUTY -> duties.applyGrant(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.access.BankDuties.Grant.class),id);
                        case BANK_DUTY_POLICY -> duties.applyPolicy(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.access.BankDuties.Policy.class),id);
                        case LENDING_ACCESS -> lending.applyGrant(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.access.LendingAccess.Grant.class),id);
                        case LOAN_FEE -> loanFees.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.LoanFeeModels.Change.class),id,request.makerSubject());
                        case LOAN_SERVICING -> loans.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.LoanServicingModels.Instruction.class),id,request.makerSubject());
                        case CREDIT_POLICY -> credit.policy(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.credit.CreditModels.Policy.class),id);
                        case CREDIT_REVIEW -> credit.review(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.credit.CreditModels.Review.class),id);
                        case CREDIT_RELEASE -> credit.release(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.credit.CreditModels.Release.class),id);
                        case TERM_SERVICING -> termServicing.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.TermServicingModels.Instruction.class),id,request.makerSubject());
                        case CONTRACT_CORRECTION -> contracts.correct(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.ContractModels.Correction.class),id,request.makerSubject());
                        case CONTRACT_OPEN -> contracts.open(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.ContractModels.Open.class),id,request.makerSubject());
                        case CONTRACT_EVENT -> contracts.event(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.contracts.ContractModels.Event.class),id,request.makerSubject());
                        case FX_BOOK -> valuations.policy(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxValuationModels.Policy.class),id);
                        case FX_CLOSING_RATE -> valuations.closingRate(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxValuationModels.ClosingRate.class),id);
                        case FX_VALUATION -> valuations.enqueue(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxValuationModels.Run.class),id,request.makerSubject());
                        case FX_TRADE -> fxTrades.enqueue(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxTradeModels.Trade.class),id,request.makerSubject());
                        case FX_REVERSAL -> fxTrades.reverse(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxTradeModels.Reversal.class),id,request.makerSubject());
                        case FX_RATE -> fx.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxModels.Rate.class),id);
                        case FX_RATE_WITHDRAW -> fx.withdraw(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.fx.FxModels.Withdraw.class),id);
                        case PAYMENT_CASE -> paymentCases.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.payments.PaymentCaseModels.Change.class),id);
                        case PAYMENT_ACCESS -> paymentAccess.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.payments.PaymentAccess.Grant.class),id);
                        case STATEMENT_ACTION -> statements.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.payments.StatementImportModels.Action.class),id,request.makerSubject());
                        case PAYMENT_ORDER -> payments.enqueue(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.payments.PaymentModels.Order.class),id,request.makerSubject());
                        case PAYMENT_RECONCILE -> payments.reconcile(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.payments.PaymentModels.Reconcile.class),id,request.makerSubject());
                        case COMPLIANCE_REVIEW -> compliance.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.compliance.ComplianceModels.Review.class),id);
                        case SERVICING_POLICY -> servicing.applyPolicy(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.servicing.ServicingModels.Policy.class),id);
                        case SERVICING_RUN -> servicing.enqueue(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.servicing.ServicingModels.Run.class),id,request.makerSubject());
                        case CASH_DENOMINATIONS -> denominations.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.cash.DenominationService.Change.class));
                        case CASH_OPERATION -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            cash.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.cash.CashModels.Change.class),id,request.makerSubject());
                        }
                        case ACCOUNT_BRANCH -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            branchOperations.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.access.BranchOperations.Assignment.class),auditReason);
                        }
                        case BRANCH_LIMIT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            branchOperations.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.access.BranchOperations.Limit.class),auditReason);
                        }
                        case SCREENING_LIST -> screening.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.compliance.AutomationModels.Activation.class),id);
                        case MONITORING_POLICY -> monitoring.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.compliance.AutomationModels.Policy.class),id);
                        case COMPLIANCE_ALERT_DISMISS -> complianceDecisions.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.compliance.AutomationModels.Dismiss.class),request.makerSubject(),id);
                        case REPORT_DEFINITION -> reportDefinitions.apply(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.reporting.ReportModels.Definition.class),id);
                        case PERIOD_CLOSE_POLICY -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            periods.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.PeriodCloseModels.Policy.class),id);
                        }
                        case PERIOD_CLOSE -> periods.enqueue(actor,request.targetId(),JSON.readValue(jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id),com.cbs.accounting.PeriodCloseModels.Run.class),id,request.makerSubject());
                        case CUSTOMER_PARTIES -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            mandates.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.mandates.MandateModels.Parties.class),id);
                        }
                        case ACCOUNT_MANDATE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            mandates.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.mandates.MandateModels.Mandate.class),id);
                        }
                        case NOTIFICATION_PREFERENCE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            notifications.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.notifications.NotificationModels.Preference.class),id);
                        }
                        case TRANSACTION_LIMIT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            com.cbs.access.TransactionLimits.apply(jdbc,tenantRepository,actor,request.targetId(),JSON.readValue(payload,com.cbs.access.TransactionLimits.Change.class),auditReason);
                        }
                        case ACCOUNT_OPERATION -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            operations.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.OperationModels.Change.class),id,request.makerSubject());
                        }
                        case ACCOUNT_FUNDING -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            funding.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.FundingModels.Change.class),id,request.makerSubject());
                        }
                        case DEPOSIT_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            accounts.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounts.AccountModels.Change.class),id,request.makerSubject());
                        }
                        case CUSTOMER_CHANGE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            customers.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.customers.CustomerModels.Change.class),id,request.makerSubject());
                        }
                        case DEPOSIT_PRODUCT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            products.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.products.ProductModels.Change.class),auditReason);
                        }
                        case DAY_CLOSE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            days.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.DayCloseModels.Change.class),id,request.makerSubject());
                        }
                        case JOURNAL_POST -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            journals.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.JournalModels.Change.class),id,request.makerSubject());
                        }
                        case LEDGER_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            ledger.enqueue(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.LedgerModels.Change.class),id,request.makerSubject());
                        }
                        case CHART_ACCOUNT -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            chart.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.accounting.ChartModels.Change.class),auditReason);
                        }
                        case BANK_SETTINGS -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            reference.apply(actor,request.targetId(),JSON.readValue(payload,com.cbs.reference.ReferenceModels.Change.class),auditReason);
                        }
                        case STAFF_UPDATE -> {
                            String payload=jdbc.queryForObject("SELECT payload::text FROM platform.approval_request WHERE id=?",String.class,id);
                            StaffUpdate update=JSON.readValue(payload,StaffUpdate.class);
                            access.update(actor,request.targetId(),new StaffUpdate(update.expectedVersion(),update.displayName(),update.email(),update.active(),auditReason));
                        }
                    }
                }
            }
            jdbc.update("UPDATE platform.approval_request SET status=?,reviewer_subject=?,decision_reason=?,decided_at=clock_timestamp() WHERE id=?",
                    next.name(),actor.subject(),reason,id);
            audit(actor,id,next.name(),reason);
            return get(actor,id,false);
        });
    }
    private Request get(Actor actor, UUID id, boolean lock) {
        var rows=jdbc.query("SELECT * FROM platform.approval_request WHERE id=? AND issuer=?"+(lock?" FOR UPDATE":""),
                (row,n)->request(row),id,actor.issuer());
        if(rows.isEmpty()) throw error(404,"APPROVAL_NOT_FOUND");
        if(lending.request(id)!=null)lending.readRequest(actor,id);else if(paymentScoped(rows.getFirst().action()))paymentAccess.require(actor,rows.getFirst().bankId(),com.cbs.payments.PaymentAccess.Capability.READ);else if(rows.getFirst().action()==Action.ACCOUNT_OPERATION)branchOperations.readRequest(actor,id);else if(rows.getFirst().action()==Action.CASH_OPERATION)cash.readRequest(actor,id,false,false);else AccessService.admin(actor);
        var request=rows.getFirst();
        if(!request.makerSubject().equals(actor.subject())){var duty=reviewDuty(actor,request);if(duty!=null&&!dutyRecovery(request))duties.read(actor,request.bankId(),duty);}
        return request;
    }
    private void audit(Actor actor, UUID id, String action, String reason) {
        jdbc.update("INSERT INTO platform.approval_event(id,request_id,actor_subject,action,reason,correlation_id) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),id,actor.subject(),action,reason,actor.correlationId());
    }
    private static Map<String,Object> staffValues(String name,String email,boolean active) {
        return Map.of("displayName",name,"email",email==null?"":email,"active",active);
    }
    private static Request request(ResultSet row) throws SQLException {
        var decided=row.getTimestamp("decided_at");
        return new Request(row.getObject("id",UUID.class),Action.valueOf(row.getString("action")),row.getObject("target_id",UUID.class),
                row.getObject("bank_id",UUID.class),row.getString("target_label"),row.getLong("expected_version"),row.getString("maker_subject"),
                row.getString("reason"),JSON.readValue(row.getString("before_values"),new TypeReference<Map<String,Object>>(){}),
                JSON.readValue(row.getString("proposed_values"),new TypeReference<Map<String,Object>>(){}),Status.valueOf(row.getString("status")),
                row.getString("reviewer_subject"),row.getString("decision_reason"),row.getTimestamp("created_at").toInstant(),decided==null?null:decided.toInstant());
    }
    private static TenantException error(int status,String code) { return new TenantException(status,code,"Approval request could not be completed"); }
}
