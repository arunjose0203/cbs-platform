package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import com.cbs.access.*;
import com.cbs.accounts.*;
import com.cbs.accounting.*;
import com.cbs.customers.CustomerService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ContractService {
    private final LendingAccess lending;
 private static final JsonMapper JSON=JsonMapper.builder().build();
    private final com.cbs.credit.CreditService credit;private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;
    private final CustomerService customers;private final ReferenceDataService reference;private final BankOperationStore customerStore;
    private final BankJournalStore bankJournals;private final JournalService journals;private final BankContractStore store;
    private final BankDatabaseProvisioner databases;private final TransactionTemplate tx;
    public ContractService(LendingAccess lending,com.cbs.credit.CreditService credit,JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,CustomerService customers,ReferenceDataService reference,BankOperationStore customerStore,BankJournalStore bankJournals,JournalService journals,BankContractStore store,BankDatabaseProvisioner databases,PlatformTransactionManager manager){this.lending=lending;this.credit=credit;this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.customers=customers;this.reference=reference;this.customerStore=customerStore;this.bankJournals=bankJournals;this.journals=journals;this.store=store;this.databases=databases;tx=new TransactionTemplate(manager);}
    public Map<String,Object> list(Actor actor,UUID bankId,Type type,int limit,int offset){AccessService.page(limit,offset);return tx.execute(t->{var bank=tenants.get(bankId,true);String filter="";if(type==Type.LOAN)filter=lending.filter(actor,bankId,"account_id");else AccessService.admin(actor);var date=reference.read(bank).businessDate();return Map.of("version",bank.version(),"businessDate",date,"storageReady",List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank),"items",jdbc.query("SELECT * FROM platform.contract WHERE tenant_id=? AND kind=?"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->{var d=effectiveDefinition(r.getString("payload"),r.getString("current_terms"));var p=JSON.readValue(r.getString("position"),Position.class);return Map.of("definition",d,"position",p,"status",r.getString("status"),"schedule",schedule(d.terms(),scale(d)),"overdue",type==Type.LOAN&&!p.closed()&&date.isAfter(d.terms().maturityDate()),"principalArrears",principalArrears(d,p,date),"principalDaysPastDue",principalDaysPastDue(d,p,date),"allowance",LoanServicingService.allowance(jdbc,d.id()).toPlainString(),"riskBook",jdbc.queryForList("SELECT allowance_ledger,expense_ledger FROM platform.loan_risk_book WHERE contract_id=?",d.id()),"pending",jdbc.queryForList("SELECT id,kind,failure_code FROM platform.contract_delivery WHERE contract_id=? AND NOT completed",d.id()));},bankId,type.name(),limit,offset),"total",jdbc.queryForObject("SELECT count(*) FROM platform.contract WHERE tenant_id=? AND kind=?"+filter,Long.class,bankId,type.name()));});}
    private long principalDaysPastDue(Definition d,Position p,LocalDate date){
        if(d.terms().type()!=Type.LOAN||p.closed())return 0;var paid=decimal(d.terms().principal()).subtract(decimal(p.principal()));var due=BigDecimal.ZERO;
        for(var row:schedule(d.terms(),scale(d))){due=due.add(decimal(row.principal()));if(row.dueDate().isBefore(date)&&due.compareTo(paid)>0)return java.time.temporal.ChronoUnit.DAYS.between(row.dueDate(),date);}return 0;
    }
    private String principalArrears(Definition d,Position p,LocalDate date){
        if(d.terms().type()!=Type.LOAN||p.closed())return "0";
        var due=schedule(d.terms(),scale(d)).stream().filter(x->x.dueDate().isBefore(date)).map(x->decimal(x.principal())).reduce(BigDecimal.ZERO,BigDecimal::add);
        return text(due.subtract(decimal(d.terms().principal()).subtract(decimal(p.principal()))).max(BigDecimal.ZERO));
    }
    private void authority(Actor actor,UUID bank,com.cbs.approvals.ApprovalModels.Action action,Object input,UUID request,boolean approve){if(lending.scoped(action,bank,input)){var scope=lending.scope(bank,action,input);if(approve)lending.checker(actor,bank,scope,request,false);else lending.maker(actor,bank,scope);}else AccessService.admin(actor);}
    public void prepare(Actor actor,UUID bankId){AccessService.admin(actor);tx.executeWithoutResult(t->{var bank=tenants.get(bankId,true);if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");databases.upgradeOwned(bank.id(),bank.databaseBinding().databaseReference());tenants.audit(actor,bankId,"CONTRACT_STORAGE_PREPARED",bank.version(),bank.status(),bank.status(),"Prepared term deposit and lending storage",null,null,Map.of());});}
    void ready(Tenant bank,long version,LocalDate date){DayCloseService.requireOpen(jdbc,bank.id());if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");if(bank.version()!=version)throw error("STALE_VERSION");if(!reference.read(bank).businessDate().equals(date))throw error("JOURNAL_BUSINESS_DATE_INVALID");if(!store.ready(bank))throw error("CONTRACT_STORAGE_REQUIRED");}
    AccountModels.Definition account(Tenant bank,UUID id,boolean financial){OperationService.requireIdle(jdbc,id);var item=accounts.current(bank.id(),id);var a=item.definition();if(!item.delivery().equals("APPLIED")||a.state()==AccountModels.State.CLOSED||a.state()==AccountModels.State.AWAITING_FUNDING||(financial&&a.state()!=AccountModels.State.ACTIVE))throw error("FUNDING_ACCOUNT_NOT_READY");accounts.requireMatching(bank,a);if(financial)customers.requireEligible(bank,a.customerId());return a;}
    void ledger(Tenant bank,AccountModels.Definition a,UUID id,String category,boolean dedicated){
        if(id!=null&&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.cash_transit WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.fx_position WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.contract_ledger WHERE ledger_id=?)",Boolean.class,id,id,id,id,id))throw error("CONTRACT_LEDGER_USED");
        if(id==null||!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.currency=? AND l.legal_entity_id=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.category=?)",Boolean.class,id,bank.id(),a.terms().currency(),a.legalEntityId(),category))throw error("CONTRACT_LEDGER_INVALID");
        if(dedicated&&(bankJournals.hasBalance(bank,id)||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.journal_ledger l JOIN platform.journal_delivery j ON j.request_id=l.request_id WHERE l.ledger_id=? AND NOT j.completed)",Boolean.class,id,id,id)))throw error("CONTRACT_LEDGER_USED");
    }
    public Posting previewOpen(Actor actor,UUID bank,Open input){return tx.execute(t->{var b=tenants.get(bank,true);authority(actor,bank,com.cbs.approvals.ApprovalModels.Action.CONTRACT_OPEN,input,null,false);return validateOpen(b,input,UUID.randomUUID());});}
    public Posting previewEvent(Actor actor,UUID bank,Event input){return tx.execute(t->{var b=tenants.get(bank,true);authority(actor,bank,com.cbs.approvals.ApprovalModels.Action.CONTRACT_EVENT,input,null,false);return validateEvent(b,input,UUID.randomUUID());});}
    public Posting validateOpen(Tenant bank,Open input,UUID id){
        ready(bank,input.expectedVersion(),input.startDate());if(referenceUsed(bank.id(),input.agreementReference()))throw error("CONTRACT_REFERENCE_IN_USE");var a=account(bank,input.accountId(),true);int scale=Currency.getInstance(a.terms().currency()).getDefaultFractionDigits();
        try{com.cbs.fx.FxModels.money(input.principal(),a.terms().currency());}catch(IllegalArgumentException e){throw error("CONTRACT_TERMS_INVALID");}
        if((input.dayBasis()!=360&&input.dayBasis()!=365)||input.maturityDate()==null||!input.maturityDate().isAfter(input.startDate())||input.maturityDate().isAfter(input.startDate().plusYears(10))||decimal(input.annualRatePercent()).signum()<0||decimal(input.annualRatePercent()).compareTo(new BigDecimal("999"))>0||decimal(input.withholdingPercent()).signum()<0||decimal(input.withholdingPercent()).compareTo(new BigDecimal("100"))>0)throw error("CONTRACT_TERMS_INVALID");
        boolean term=input.type()==Type.TERM_DEPOSIT;
        if(!term)credit.requireDisbursement(bank,input,id);
        if(term&&input.instalments()!=0||!term&&(decimal(input.withholdingPercent()).signum()!=0||input.taxLedger()!=null))throw error("CONTRACT_TERMS_INVALID");
        try{schedule(input,scale);}catch(IllegalArgumentException e){throw error("CONTRACT_TERMS_INVALID");}
        var ids=new ArrayList<>(List.of(input.principalLedger(),input.accruedInterestLedger(),input.interestProfitLossLedger()));if(input.taxLedger()!=null)ids.add(input.taxLedger());if(new HashSet<>(ids).size()!=ids.size())throw error("CONTRACT_LEDGER_INVALID");
        ledger(bank,a,input.principalLedger(),term?"LIABILITY":"ASSET",true);ledger(bank,a,input.accruedInterestLedger(),term?"LIABILITY":"ASSET",true);ledger(bank,a,input.interestProfitLossLedger(),term?"EXPENSE":"INCOME",false);if(decimal(input.withholdingPercent()).signum()>0)ledger(bank,a,input.taxLedger(),"LIABILITY",false);
        TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),term?"TD_OPEN":"LOAN_DISBURSE",input.startDate(),decimal(input.principal()));
        var d=new Definition(id,bank.id(),a,input);var before=new Position("0","0","0",input.startDate(),false);var after=new Position(input.principal(),"0","0",input.startDate(),false);
        var customer=customerStore.position(bank,a);if(!customer.reconciled())throw error("FUNDING_UNRECONCILED");if(term&&decimal(customer.withdrawable()).compareTo(decimal(input.principal()))<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
        var lines=List.of(new JournalModels.Line(customer.ledgerId(),term?JournalModels.Side.DEBIT:JournalModels.Side.CREDIT,input.principal()),new JournalModels.Line(input.principalLedger(),term?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,input.principal()));
        return posting(bank,id,d,Kind.OPEN,input.startDate(),before,after,term?text(decimal(input.principal()).negate()):input.principal(),"0",lines,input.reason());
    }
    private Definition effectiveDefinition(String payload,String terms) {var d=JSON.readValue(payload,Definition.class);return terms==null?d:new Definition(d.id(),d.bankId(),d.account(),JSON.readValue(terms,Open.class));}
    boolean referenceUsed(UUID bank,String reference){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract WHERE tenant_id=? AND agreement_reference=?) OR EXISTS(SELECT 1 FROM platform.contract_term_revision WHERE tenant_id=? AND agreement_reference=?)",Boolean.class,bank,reference,bank,reference);}
    public Definition definition(UUID bank,UUID id){var rows=jdbc.query("SELECT payload,current_terms FROM platform.contract WHERE tenant_id=? AND id=?",(r,n)->effectiveDefinition(r.getString(1),r.getString(2)),bank,id);if(rows.isEmpty())throw error("CONTRACT_NOT_FOUND");return rows.getFirst();}
    public Posting validateEvent(Tenant bank,Event input,UUID id){
        ready(bank,input.expectedVersion(),input.businessDate());var original=definition(bank.id(),input.contractId());
        if(input.kind()==Kind.OPEN||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,input.contractId()))throw error("CONTRACT_PENDING");
        var a=account(bank,original.account().id(),input.kind()!=Kind.ACCRUE);var d=new Definition(original.id(),bank.id(),a,original.terms());var before=store.position(bank,d.id());if(before.closed())throw error("CONTRACT_CLOSED");
        boolean term=d.terms().type()==Type.TERM_DEPOSIT;var principal=decimal(before.principal());var interest=decimal(before.interest());var amount=decimal(input.amount());String movement="0",tax="0";Position after;List<JournalModels.Line> lines=new ArrayList<>();var customer=customerStore.position(bank,a);if(!customer.reconciled())throw error("FUNDING_UNRECONCILED");
        if(input.kind()==Kind.ACCRUE){
            LocalDate through=term&&input.businessDate().isAfter(d.terms().maturityDate())?d.terms().maturityDate():input.businessDate();
            if(amount.signum()!=0||!through.isAfter(before.accruedThrough()))throw error("CONTRACT_ACCRUAL_INVALID");
            var calc=interest(before.principal(),d.terms().annualRatePercent(),d.terms().dayBasis(),before.accruedThrough(),through,before.carry(),scale(d));
            if(decimal(calc.interest()).signum()>0){lines.add(new JournalModels.Line(d.terms().accruedInterestLedger(),term?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,calc.interest()));lines.add(new JournalModels.Line(d.terms().interestProfitLossLedger(),term?JournalModels.Side.DEBIT:JournalModels.Side.CREDIT,calc.interest()));}
            after=new Position(before.principal(),text(interest.add(decimal(calc.interest()))),calc.carry(),through,false);
        }else{
            LocalDate through=term&&input.businessDate().isAfter(d.terms().maturityDate())?d.terms().maturityDate():input.businessDate();if(!before.accruedThrough().equals(through))throw error("CONTRACT_ACCRUAL_REQUIRED");
            if(term&&input.kind()==Kind.REDEEM){
                if(input.businessDate().isBefore(d.terms().maturityDate())||amount.compareTo(principal)!=0)throw error("CONTRACT_REDEMPTION_INVALID");
                var withheld=interest.multiply(decimal(d.terms().withholdingPercent())).divide(new BigDecimal("100"),scale(d),RoundingMode.HALF_EVEN);tax=text(withheld);movement=text(principal.add(interest).subtract(withheld));
                lines.add(new JournalModels.Line(d.terms().principalLedger(),JournalModels.Side.DEBIT,text(principal)));if(interest.signum()>0)lines.add(new JournalModels.Line(d.terms().accruedInterestLedger(),JournalModels.Side.DEBIT,text(interest)));if(withheld.signum()>0)lines.add(new JournalModels.Line(d.terms().taxLedger(),JournalModels.Side.CREDIT,tax));lines.add(new JournalModels.Line(customer.ledgerId(),JournalModels.Side.CREDIT,movement));
                after=new Position("0","0","0",through,true);TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),"TD_REDEEM",input.businessDate(),decimal(movement));
            }else if(!term&&input.kind()==Kind.REPAY){
                try{com.cbs.fx.FxModels.money(input.amount(),a.terms().currency());}catch(IllegalArgumentException e){throw error("CONTRACT_REPAYMENT_INVALID");}
                if(amount.compareTo(principal.add(interest))>0)throw error("CONTRACT_REPAYMENT_INVALID");if(decimal(customer.withdrawable()).compareTo(amount)<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
                if(LoanServicingService.allowance(jdbc,d.id()).compareTo(principal.add(interest).subtract(amount))>0)throw error("LOAN_ALLOWANCE_RELEASE_REQUIRED");
                var interestPaid=amount.min(interest);var principalPaid=amount.subtract(interestPaid);movement=text(amount.negate());lines.add(new JournalModels.Line(customer.ledgerId(),JournalModels.Side.DEBIT,text(amount)));if(interestPaid.signum()>0)lines.add(new JournalModels.Line(d.terms().accruedInterestLedger(),JournalModels.Side.CREDIT,text(interestPaid)));if(principalPaid.signum()>0)lines.add(new JournalModels.Line(d.terms().principalLedger(),JournalModels.Side.CREDIT,text(principalPaid)));
                after=new Position(text(principal.subtract(principalPaid)),text(interest.subtract(interestPaid)),before.carry(),through,amount.compareTo(principal.add(interest))==0&&LoanFeeService.outstanding(jdbc,d.id()).signum()==0);TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),"LOAN_REPAY",input.businessDate(),amount);
            }else throw error("CONTRACT_EVENT_INVALID");
        }
        return posting(bank,id,d,input.kind(),input.businessDate(),before,after,movement,tax,lines,input.reason());
    }
    public Map<String,Object> history(Actor actor,UUID bankId,UUID contractId,int limit,int offset) {
        AccessService.page(limit,offset);
        return tx.execute(t->{var bank=tenants.get(bankId,true);var checked=definition(bankId,contractId);if(checked.terms().type()==Type.LOAN)lending.read(actor,bankId,checked.account().id());else AccessService.admin(actor);var date=reference.read(bank).businessDate();
            boolean ready=store.ready(bank);UUID latest=ready?store.latestUnreversed(bank,contractId):null;
            return Map.of("version",bank.version(),"businessDate",date,"items",jdbc.query("SELECT d.*,EXISTS(SELECT 1 FROM platform.contract_reversal r WHERE r.original_id=d.id) reversed FROM platform.contract_delivery d WHERE d.contract_id=? AND d.tenant_id=? ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?",(r,n)->{
                var posting=JSON.readValue(r.getString("payload"),Posting.class);boolean completed=r.getBoolean("completed"),reversed=r.getBoolean("reversed");
                boolean eligible=completed&&!reversed&&posting.id().equals(latest)&&posting.businessDate().equals(date)&&Set.of(Kind.OPEN,Kind.ACCRUE,Kind.REPAY).contains(posting.kind());
                return Map.of("posting",posting,"completed",completed,"reversed",reversed,"reversible",eligible,"maker",r.getString("maker_subject"),"checker",r.getString("reviewer_subject"),"failureCode",Objects.toString(r.getString("failure_code"),""));
            },contractId,bankId,limit,offset),"total",jdbc.queryForObject("SELECT count(*) FROM platform.contract_delivery WHERE contract_id=? AND tenant_id=?",Long.class,contractId,bankId));
        });
    }
    public Posting previewCorrection(Actor actor,UUID bank,Correction input){return tx.execute(t->{var b=tenants.get(bank,true);authority(actor,bank,com.cbs.approvals.ApprovalModels.Action.CONTRACT_CORRECTION,input,null,false);return validateCorrection(b,input,UUID.randomUUID());});}
    public Posting validateCorrection(Tenant bank,Correction input,UUID id) {
        ready(bank,input.expectedVersion(),input.businessDate());var originalDefinition=definition(bank.id(),input.contractId());
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,input.contractId()))throw error("CONTRACT_PENDING");
        var rows=jdbc.queryForList("SELECT payload::text FROM platform.contract_delivery WHERE id=? AND tenant_id=? AND contract_id=? AND completed",String.class,input.originalEventId(),bank.id(),input.contractId());
        if(rows.isEmpty())throw error("CONTRACT_CORRECTION_INVALID");var original=JSON.readValue(rows.getFirst(),Posting.class);
        if(!Set.of(Kind.OPEN,Kind.ACCRUE,Kind.REPAY).contains(original.kind())||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_reversal WHERE original_id=?)",Boolean.class,input.originalEventId()))throw error("CONTRACT_CORRECTION_INVALID");
        if(!original.businessDate().equals(input.businessDate()))throw error("CONTRACT_CORRECTION_CLOSED_DAY");
        if(!input.originalEventId().equals(store.latestUnreversed(bank,input.contractId())))throw error("CONTRACT_CORRECTION_DEPENDENCIES");
        var before=store.position(bank,input.contractId());if(!before.equals(original.after()))throw error("CONTRACT_POSITION_CHANGED");
        var a=account(bank,originalDefinition.account().id(),original.kind()!=Kind.ACCRUE);
        var d=new Definition(originalDefinition.id(),bank.id(),a,originalDefinition.terms());
        var after=original.kind()==Kind.OPEN?new Position("0","0","0",original.before().accruedThrough(),true):original.before();
        var movement=decimal(original.customerMovement()).negate();var balance=customerStore.position(bank,a);
        if(!balance.reconciled())throw error("FUNDING_UNRECONCILED");
        if(movement.signum()<0&&decimal(balance.withdrawable()).compareTo(movement.abs())<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
        if(movement.signum()!=0)TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),d.terms().type()==Type.TERM_DEPOSIT?"TD_OPEN_REVERSAL":original.kind()==Kind.OPEN?"LOAN_DISBURSE_REVERSAL":"LOAN_REPAY_REVERSAL",input.businessDate(),movement.abs());
        var lines=original.journal()==null?List.<JournalModels.Line>of():original.journal().lines().stream().map(l->new JournalModels.Line(l.ledgerId(),l.side()==JournalModels.Side.DEBIT?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,l.amount())).toList();
        var p=posting(bank,id,d,Kind.REVERSE,input.businessDate(),before,after,text(movement),text(decimal(original.tax()).negate()),lines,input.reason());
        return new Posting(p.id(),p.contract(),p.kind(),p.businessDate(),p.before(),p.after(),p.customerMovement(),p.tax(),p.journal(),p.reason(),original.id());
    }
    public void correct(Actor actor,UUID bankId,Correction input,UUID id,String maker){var bank=tenants.get(bankId,true);authority(actor,bankId,com.cbs.approvals.ApprovalModels.Action.CONTRACT_CORRECTION,input,id,true);save(actor,bank,validateCorrection(bank,input,id),maker);}
    Posting posting(Tenant bank,UUID id,Definition d,Kind kind,LocalDate date,Position before,Position after,String movement,String tax,List<JournalModels.Line> lines,String reason){
        JournalModels.Entry journal=null;if(!lines.isEmpty()){var change=new JournalModels.Change(bank.version(),"CONTRACT-"+id,reason,date,d.account().legalEntityId(),d.account().terms().currency(),lines,null,reason);journals.validateContract(bank,change);journal=new JournalModels.Entry(id,bank.id(),change.reference(),reason,date,change.legalEntityId(),change.currency(),List.copyOf(lines),null);}
        return new Posting(id,d,kind,date,before,after,movement,tax,journal,reason);
    }
    public Map<String,Object> values(Posting p){var result=new LinkedHashMap<String,Object>();if(p.originalEventId()!=null)result.put("originalEventId",p.originalEventId());if(p.servicing()!=null)result.put("servicing",p.servicing());if(p.loanServicing()!=null)result.put("loanServicing",p.loanServicing());if(p.feeServicing()!=null)result.put("feeServicing",p.feeServicing());result.putAll(Map.of("contract",p.contract(),"kind",p.kind(),"before",p.before(),"after",p.after(),"customerMovement",p.customerMovement(),"tax",p.tax(),"schedule",schedule(p.contract().terms(),scale(p.contract()))));return result;}
    public void open(Actor actor,UUID bank,Open input,UUID id,String maker){var b=tenants.get(bank,true);authority(actor,bank,com.cbs.approvals.ApprovalModels.Action.CONTRACT_OPEN,input,id,true);save(actor,b,validateOpen(b,input,id),maker);}
    public void event(Actor actor,UUID bank,Event input,UUID id,String maker){var b=tenants.get(bank,true);authority(actor,bank,com.cbs.approvals.ApprovalModels.Action.CONTRACT_EVENT,input,id,true);save(actor,b,validateEvent(b,input,id),maker);}
    void save(Actor actor,Tenant bank,Posting p,String maker){
        var d=p.contract();if(p.kind()==Kind.OPEN){jdbc.update("INSERT INTO platform.contract(id,tenant_id,account_id,kind,payload,position,status) VALUES (?,?,?,?,?::jsonb,?::jsonb,'AWAITING_POSTING')",d.id(),bank.id(),d.account().id(),d.terms().type().name(),JSON.writeValueAsString(d),JSON.writeValueAsString(p.before()));if(d.terms().type()==Type.LOAN)credit.reserve(bank,d.terms(),d.id());for(UUID ledger:List.of(d.terms().principalLedger(),d.terms().accruedInterestLedger()))jdbc.update("INSERT INTO platform.contract_ledger(ledger_id,contract_id) VALUES (?,?)",ledger,d.id());}
        jdbc.update("INSERT INTO platform.contract_delivery(id,tenant_id,contract_id,account_id,kind,business_date,amount,currency,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",p.id(),bank.id(),d.id(),d.account().id(),p.kind().name(),p.businessDate(),decimal(p.customerMovement()).abs(),d.account().terms().currency(),JSON.writeValueAsString(p),maker,actor.issuer(),actor.subject(),actor.correlationId());
        if(p.feeServicing()!=null&&!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.loan_fee_book WHERE contract_id=?)",Boolean.class,d.id())){var f=p.feeServicing();jdbc.update("INSERT INTO platform.loan_fee_book(contract_id,receivable_ledger,income_ledger) VALUES (?,?,?)",d.id(),f.receivableLedger(),f.incomeLedger());jdbc.update("INSERT INTO platform.contract_ledger(ledger_id,contract_id) VALUES (?,?)",f.receivableLedger(),d.id());}
        if(p.loanServicing()!=null){var l=p.loanServicing();var i=l.instruction();if(p.kind()==Kind.RESTRUCTURE){credit.reserve(bank,l.renewedTerms(),d.id());jdbc.update("INSERT INTO platform.contract_term_revision(id,tenant_id,contract_id,agreement_reference,terms) VALUES (?,?,?,?,?::jsonb)",p.id(),bank.id(),d.id(),l.renewedTerms().agreementReference(),JSON.writeValueAsString(l.renewedTerms()));}else if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.loan_risk_book WHERE contract_id=?)",Boolean.class,d.id())){jdbc.update("INSERT INTO platform.loan_risk_book(contract_id,allowance_ledger,expense_ledger) VALUES (?,?,?)",d.id(),i.allowanceLedger(),i.impairmentExpenseLedger());jdbc.update("INSERT INTO platform.contract_ledger(ledger_id,contract_id) VALUES (?,?)",i.allowanceLedger(),d.id());}}
        if(p.kind()==Kind.RENEW)jdbc.update("INSERT INTO platform.contract_term_revision(id,tenant_id,contract_id,agreement_reference,terms) VALUES (?,?,?,?,?::jsonb)",p.id(),bank.id(),d.id(),p.servicing().renewedTerms().agreementReference(),JSON.writeValueAsString(p.servicing().renewedTerms()));
        if(p.kind()==Kind.REVERSE)jdbc.update("INSERT INTO platform.contract_reversal(id,original_id,contract_id) VALUES (?,?,?)",p.id(),p.originalEventId(),d.id());
        if(p.journal()!=null){var j=p.journal();jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",j.id(),bank.id(),j.reference(),JSON.writeValueAsString(j),maker,actor.issuer(),actor.subject(),actor.correlationId());for(var l:j.lines())jdbc.update("INSERT INTO platform.journal_ledger VALUES (?,?)",j.id(),l.ledgerId());}
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bank.id(),"CONTRACT_"+p.kind()+"_APPROVED",bank.version()+1,bank.status(),bank.status(),p.reason(),null,null,values(p));
    }
    public List<UUID> pending(){return jdbc.queryForList("SELECT id FROM platform.contract_delivery WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",UUID.class);}
    public void retry(Actor actor,UUID bank,UUID id){var scope=lending.request(id);if(scope!=null){var g=lending.read(actor,bank,scope.accountId());if(!g.roles().contains(LendingAccess.Role.FINANCE_OFFICER)&&!g.roles().contains(LendingAccess.Role.CREDIT_APPROVER))throw new TenantException(403,"LENDING_ROLE_REQUIRED","Posting authority required");}else AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bank,actor.issuer()))throw error("APPROVAL_NOT_FOUND");deliver(id);}
    public void deliver(UUID id){tx.executeWithoutResult(t->{var banks=jdbc.queryForList("SELECT tenant_id FROM platform.contract_delivery WHERE id=?",UUID.class,id);if(banks.isEmpty())return;var bank=tenants.get(banks.getFirst(),true);var jobs=jdbc.query("SELECT * FROM platform.contract_delivery WHERE id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Posting.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);if(jobs.isEmpty())return;var job=jobs.getFirst();var p=job.posting();String failure=null;
        try{if(!store.posted(bank,id)){if(p.contract().terms().type()==Type.LOAN){var scope=lending.request(id);if(scope==null)throw new TenantException(403,"LENDING_REAUTHORIZATION_REQUIRED","Legacy pending loan requires authority review");lending.maker(new Actor(job.issuer(),job.maker(),false,job.correlationId()),bank.id(),scope);lending.checker(new Actor(job.issuer(),job.checker(),false,job.correlationId()),bank.id(),scope,id,false);}if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");accounts.requireMatching(bank,p.contract().account());if(p.kind()==Kind.OPEN&&p.contract().terms().type()==Type.LOAN)credit.requireDisbursement(bank,p.contract().terms(),p.contract().id());if(p.kind()==Kind.RESTRUCTURE)credit.requireDisbursement(bank,p.loanServicing().renewedTerms(),p.contract().id());if(!Set.of(Kind.ACCRUE,Kind.IMPAIR,Kind.WRITE_OFF,Kind.FEE_ASSESS,Kind.FEE_WAIVE).contains(p.kind())&&!(p.kind()==Kind.REVERSE&&decimal(p.customerMovement()).signum()==0))customers.requireEligible(bank,p.contract().account().customerId());}store.post(bank,job);}catch(TenantException e){failure=e.code();}
        jdbc.update("UPDATE platform.contract_delivery SET completed=?,failure_code=?,attempts=attempts+1,last_attempt_at=clock_timestamp() WHERE id=?",failure==null,failure,id);jdbc.update("UPDATE platform.journal_delivery SET completed=?,failure_code=?,completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE request_id=?",failure==null,failure,failure==null,id);
        if(failure==null){if(p.kind()==Kind.RESTRUCTURE)jdbc.update("UPDATE platform.contract SET current_terms=?::jsonb WHERE id=?",JSON.writeValueAsString(p.loanServicing().renewedTerms()),p.contract().id());if(p.kind()==Kind.RENEW)jdbc.update("UPDATE platform.contract SET current_terms=?::jsonb WHERE id=?",JSON.writeValueAsString(p.servicing().renewedTerms()),p.contract().id());jdbc.update("UPDATE platform.contract SET position=?::jsonb,status=? WHERE id=?",JSON.writeValueAsString(p.after()),p.kind()==Kind.REVERSE&&p.after().closed()?"CANCELLED":p.kind()==Kind.WRITE_OFF?"WRITTEN_OFF":p.after().closed()?"CLOSED":"ACTIVE",p.contract().id());tenants.audit(new Actor(job.issuer(),job.checker(),true,job.correlationId()),bank.id(),"CONTRACT_"+p.kind()+"_POSTED",bank.version(),bank.status(),bank.status(),p.reason(),null,null,Map.of("contractId",p.contract().id(),"eventId",id));}
    });}
    private static TenantException error(String code){return new TenantException(409,code,"Contract requires attention");}
}
