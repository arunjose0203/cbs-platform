package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import static com.cbs.contracts.TermServicingModels.*;
import com.cbs.access.*;
import com.cbs.accounts.BankOperationStore;
import com.cbs.accounting.JournalModels;
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

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class TermServicingService {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ContractService contracts;
    private final TenantRepository tenants;
    private final BankContractStore store;
    private final BankOperationStore customers;
    private final ReferenceDataService reference;
    private final TransactionTemplate tx;
    public TermServicingService(JdbcTemplate jdbc,ContractService contracts,TenantRepository tenants,BankContractStore store,
            BankOperationStore customers,ReferenceDataService reference,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.contracts=contracts;this.tenants=tenants;this.store=store;this.customers=customers;this.reference=reference;tx=new TransactionTemplate(manager);
    }
    public Posting preview(Actor actor,UUID bank,Instruction input){AccessService.admin(actor);return tx.execute(t->validate(tenants.get(bank,true),input,UUID.randomUUID()));}
    public Posting validate(Tenant bank,Instruction input,UUID id) {
        contracts.ready(bank,input.expectedVersion(),input.businessDate());
        var original=contracts.definition(bank.id(),input.contractId());
        if(original.terms().type()!=Type.TERM_DEPOSIT)throw error("TERM_SERVICING_ONLY");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,input.contractId()))throw error("CONTRACT_PENDING");
        var a=contracts.account(bank,original.account().id(),true);var d=new Definition(original.id(),bank.id(),a,original.terms());
        var before=store.position(bank,d.id());if(before.closed())throw error("CONTRACT_CLOSED");
        LocalDate through=input.businessDate().isAfter(d.terms().maturityDate())?d.terms().maturityDate():input.businessDate();
        if(!before.accruedThrough().equals(through))throw error("CONTRACT_ACCRUAL_REQUIRED");
        var customer=customers.position(bank,a);if(!customer.reconciled())throw error("FUNDING_UNRECONCILED");
        BigDecimal principal=decimal(before.principal()),interest=decimal(before.interest()),penalty=decimal(input.penaltyAmount());
        if(penalty.signum()<0||penalty.stripTrailingZeros().scale()>scale(d)||penalty.compareTo(interest)>0)throw error("TERM_PENALTY_INVALID");
        var basis=input.taxBasis()==TaxBasis.GROSS_INTEREST?interest:interest.subtract(penalty);
        var tax=basis.multiply(decimal(d.terms().withholdingPercent())).divide(new BigDecimal("100"),scale(d),RoundingMode.HALF_EVEN);
        if(penalty.add(tax).compareTo(interest)>0)throw error("TERM_PENALTY_INVALID");
        var net=interest.subtract(penalty).subtract(tax);var lines=new ArrayList<JournalModels.Line>();
        Open renewed=null;Position after;String movement;Kind kind;
        if(input.operation()==Operation.EARLY_CLOSE) {
            if(!input.businessDate().isBefore(d.terms().maturityDate())||input.interestDisposition()!=null||input.renewalAnnualRatePercent()!=null||input.renewalMaturityDate()!=null)throw error("TERM_EARLY_CLOSE_INVALID");
            if(penalty.signum()>0)contracts.ledger(bank,a,input.penaltyIncomeLedger(),"INCOME",false);
            else if(input.penaltyIncomeLedger()!=null)throw error("TERM_PENALTY_INVALID");
            kind=Kind.EARLY_CLOSE;movement=text(principal.add(net));after=new Position("0","0","0",through,true);
            add(lines,d.terms().principalLedger(),JournalModels.Side.DEBIT,principal);
            add(lines,customer.ledgerId(),JournalModels.Side.CREDIT,decimal(movement));
            if(penalty.signum()>0)add(lines,input.penaltyIncomeLedger(),JournalModels.Side.CREDIT,penalty);
            TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),"TD_EARLY_CLOSE",input.businessDate(),decimal(movement));
        } else {
            if(input.businessDate().isBefore(d.terms().maturityDate())||penalty.signum()!=0||input.penaltyIncomeLedger()!=null||input.taxBasis()!=TaxBasis.GROSS_INTEREST||input.interestDisposition()==null||input.renewalAnnualRatePercent()==null||input.renewalMaturityDate()==null||!input.renewalMaturityDate().isAfter(input.businessDate())||input.renewalMaturityDate().isAfter(input.businessDate().plusYears(10)))throw error("TERM_RENEWAL_INVALID");
            var rate=decimal(input.renewalAnnualRatePercent());if(rate.signum()<0||rate.compareTo(new BigDecimal("999"))>0)throw error("TERM_RENEWAL_INVALID");
            if(contracts.referenceUsed(bank.id(),input.agreementReference()))throw error("CONTRACT_REFERENCE_IN_USE");
            kind=Kind.RENEW;boolean capital=input.interestDisposition()==InterestDisposition.CAPITALIZE_NET_INTEREST;
            BigDecimal renewedPrincipal=capital?principal.add(net):principal;
            movement=capital?"0":text(net);
            add(lines,capital?d.terms().principalLedger():customer.ledgerId(),JournalModels.Side.CREDIT,net);
            renewed=new Open(input.expectedVersion(),Type.TERM_DEPOSIT,a.id(),d.terms().principalLedger(),d.terms().accruedInterestLedger(),d.terms().interestProfitLossLedger(),d.terms().taxLedger(),text(renewedPrincipal),input.renewalAnnualRatePercent(),d.terms().withholdingPercent(),d.terms().dayBasis(),input.businessDate(),input.renewalMaturityDate(),0,input.agreementReference(),input.reason());
            after=new Position(text(renewedPrincipal),"0","0",input.businessDate(),false);
            TransactionLimits.check(jdbc,bank.id(),a.terms().currency(),"TD_RENEW",input.businessDate(),renewedPrincipal);
        }
        add(lines,d.terms().accruedInterestLedger(),JournalModels.Side.DEBIT,interest);
        if(tax.signum()>0)add(lines,d.terms().taxLedger(),JournalModels.Side.CREDIT,tax);
        var p=contracts.posting(bank,id,d,kind,input.businessDate(),before,after,movement,text(tax),lines,input.reason());
        return new Posting(p.id(),d,kind,p.businessDate(),before,after,movement,p.tax(),p.journal(),p.reason(),null,new Details(input,renewed,text(penalty)));
    }
    public void apply(Actor actor,UUID bankId,Instruction input,UUID id,String maker){AccessService.admin(actor);var bank=tenants.get(bankId,true);contracts.save(actor,bank,validate(bank,input,id),maker);}
    public Map<String,Object> alerts(Actor actor,UUID bankId,int days,int limit,int offset) {
        AccessService.admin(actor);AccessService.page(limit,offset);if(days<0||days>90)throw error("TERM_NOTICE_WINDOW_INVALID");
        return tx.execute(t->{var bank=tenants.get(bankId,true);var date=reference.read(bank).businessDate();
            String filter=" FROM platform.contract WHERE tenant_id=? AND kind='TERM_DEPOSIT' AND status='ACTIVE' AND (coalesce(current_terms,payload->'terms')->>'maturityDate')::date<=?";
            var rows=jdbc.query("SELECT id,coalesce(current_terms,payload->'terms') terms,payload->'account'->>'accountNumber' account_number,position"+filter+" ORDER BY (coalesce(current_terms,payload->'terms')->>'maturityDate')::date,id LIMIT ? OFFSET ?",(r,n)->{
                var terms=JSON.readValue(r.getString("terms"),Open.class);
                return Map.of("contractId",r.getObject("id",UUID.class),"agreementReference",terms.agreementReference(),"accountNumber",r.getString("account_number"),"maturityDate",terms.maturityDate(),"daysToMaturity",java.time.temporal.ChronoUnit.DAYS.between(date,terms.maturityDate()),"status",date.isBefore(terms.maturityDate())?"DUE_SOON":"MATURED_ACTION_REQUIRED");
            },bankId,date.plusDays(days),limit,offset);
            return Map.of("businessDate",date,"leadDays",days,"items",rows,"total",jdbc.queryForObject("SELECT count(*)"+filter,Long.class,bankId,date.plusDays(days)),"channel","IN_APP_OPERATIONS");
        });
    }
    private void add(List<JournalModels.Line> lines,UUID ledger,JournalModels.Side side,BigDecimal amount){if(amount.signum()>0)lines.add(new JournalModels.Line(ledger,side,text(amount)));}
    private static TenantException error(String code){return new TenantException(409,code,"Term-deposit servicing requires attention");}
}
