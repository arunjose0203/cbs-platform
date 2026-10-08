package com.cbs.contracts;
import static com.cbs.contracts.ContractModels.*;
import com.cbs.access.*;
import com.cbs.accounting.*;
import com.cbs.credit.CreditService;
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
public class LoanServicingService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ContractService contracts;private final BankContractStore store;private final CreditService credit;private final AccessService access;private final TransactionTemplate tx;
 public LoanServicingService(JdbcTemplate jdbc,TenantRepository tenants,ContractService contracts,BankContractStore store,CreditService credit,AccessService access,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.contracts=contracts;this.store=store;this.credit=credit;this.access=access;tx=new TransactionTemplate(manager);}
 private static TenantException error(String c){return new TenantException(409,c,"Loan servicing requires attention");}
 public static BigDecimal allowance(JdbcTemplate jdbc,UUID contract){var rows=jdbc.queryForList("SELECT (payload->'loanServicing'->>'allowanceAfter')::numeric FROM platform.contract_delivery WHERE contract_id=? AND completed AND payload->'loanServicing' IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1",BigDecimal.class,contract);return rows.isEmpty()?BigDecimal.ZERO:rows.getFirst();}
 public Posting preview(Actor actor,UUID bank,LoanServicingModels.Instruction i){AccessService.admin(actor);return tx.execute(t->validate(tenants.get(bank,true),i,UUID.randomUUID()));}
 public Posting validate(Tenant b,LoanServicingModels.Instruction i,UUID id){
  contracts.ready(b,i.expectedVersion(),i.businessDate());var d=contracts.definition(b.id(),i.contractId());if(d.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,d.id()))throw error("CONTRACT_PENDING");
  var before=store.position(b,d.id());if(before.closed())throw error("CONTRACT_CLOSED");if(!before.accruedThrough().equals(i.businessDate()))throw error("CONTRACT_ACCRUAL_REQUIRED");
  var a=contracts.account(b,d.account().id(),false);d=new Definition(d.id(),b.id(),a,d.terms());var exposure=decimal(before.principal()).add(decimal(before.interest()));var old=allowance(jdbc,d.id());var target=decimal(i.allowance());
  if(target.signum()<0||target.scale()>scale(d)||target.compareTo(exposure)>0)throw error("LOAN_ALLOWANCE_INVALID");
  if(i.operation()!=LoanServicingModels.Operation.IMPAIR&&LoanFeeService.outstanding(jdbc,d.id()).signum()>0)throw error("LOAN_FEES_OUTSTANDING");
  Position after=before;Open renewed=null;List<JournalModels.Line> lines=new ArrayList<>();Kind kind=Kind.valueOf(i.operation().name());
  if(kind==Kind.RESTRUCTURE){
   if(old.signum()!=0||target.signum()!=0||decimal(before.interest()).signum()!=0||i.allowanceLedger()!=null||i.impairmentExpenseLedger()!=null||i.newAnnualRatePercent()==null||i.newInstalments()<1)throw error("LOAN_RESTRUCTURE_INVALID");
   if(contracts.referenceUsed(b.id(),i.evidenceReference()))throw error("CONTRACT_REFERENCE_IN_USE");
   var o=d.terms();renewed=new Open(b.version(),Type.LOAN,a.id(),o.principalLedger(),o.accruedInterestLedger(),o.interestProfitLossLedger(),null,before.principal(),i.newAnnualRatePercent(),"0",o.dayBasis(),i.businessDate(),i.businessDate().plusMonths(i.newInstalments()),i.newInstalments(),i.evidenceReference(),i.reason());schedule(renewed,scale(d));credit.requireDisbursement(b,renewed,d.id());
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.credit_reservation r JOIN platform.credit_application a ON a.id=r.application_id WHERE a.tenant_id=? AND a.reference=?)",Boolean.class,b.id(),i.evidenceReference()))throw error("CREDIT_ALREADY_USED");
   after=new Position(before.principal(),"0",before.carry(),i.businessDate(),false);
  }else{
   if(i.newAnnualRatePercent()!=null||i.newInstalments()!=0||i.allowanceLedger()==null||i.impairmentExpenseLedger()==null||i.allowanceLedger().equals(i.impairmentExpenseLedger()))throw error("LOAN_ALLOWANCE_INVALID");
   var registry=jdbc.queryForList("SELECT allowance_ledger,expense_ledger FROM platform.loan_risk_book WHERE contract_id=?",d.id());
   if(registry.isEmpty()){contracts.ledger(b,a,i.allowanceLedger(),"ASSET",true);contracts.ledger(b,a,i.impairmentExpenseLedger(),"EXPENSE",false);if(!jdbc.queryForObject("SELECT c.normal_balance='CREDIT' FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=?",Boolean.class,i.allowanceLedger()))throw error("LOAN_ALLOWANCE_LEDGER_INVALID");}
   else if(!i.allowanceLedger().equals(registry.getFirst().get("allowance_ledger"))||!i.impairmentExpenseLedger().equals(registry.getFirst().get("expense_ledger")))throw error("LOAN_ALLOWANCE_LEDGER_INVALID");
   if(kind==Kind.IMPAIR){var delta=target.subtract(old);if(delta.signum()==0)throw error("LOAN_ALLOWANCE_UNCHANGED");lines.add(new JournalModels.Line(i.impairmentExpenseLedger(),delta.signum()>0?JournalModels.Side.DEBIT:JournalModels.Side.CREDIT,text(delta.abs())));lines.add(new JournalModels.Line(i.allowanceLedger(),delta.signum()>0?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,text(delta.abs())));}
   else {if(target.signum()!=0||old.compareTo(exposure)!=0||i.stage()!=3)throw error("LOAN_FULL_ALLOWANCE_REQUIRED");lines.add(new JournalModels.Line(i.allowanceLedger(),JournalModels.Side.DEBIT,text(old)));if(decimal(before.principal()).signum()>0)lines.add(new JournalModels.Line(d.terms().principalLedger(),JournalModels.Side.CREDIT,before.principal()));if(decimal(before.interest()).signum()>0)lines.add(new JournalModels.Line(d.terms().accruedInterestLedger(),JournalModels.Side.CREDIT,before.interest()));after=new Position("0","0","0",i.businessDate(),true);}
  }
  TransactionLimits.check(jdbc,b.id(),a.terms().currency(),"LOAN_"+kind.name(),i.businessDate(),kind==Kind.IMPAIR?target.subtract(old).abs():exposure);
  var p=contracts.posting(b,id,d,kind,i.businessDate(),before,after,"0","0",lines,i.reason());return new Posting(p.id(),p.contract(),p.kind(),p.businessDate(),p.before(),p.after(),p.customerMovement(),p.tax(),p.journal(),p.reason(),null,null,new LoanServicingModels.Details(i,text(old),text(target),renewed));
 }
 public void apply(Actor actor,UUID bank,LoanServicingModels.Instruction i,UUID id,String maker){AccessService.admin(actor);var b=tenants.get(bank,true);var p=validate(b,i,id);credit.requireReviewer(actor,b,p.contract().account().terms().currency(),decimal(p.before().principal()).add(decimal(p.before().interest())));contracts.save(actor,b,p,maker);}
 public Map<String,Object> collections(Actor actor,UUID bank,UUID contract){AccessService.admin(actor);return tx.execute(t->{var b=tenants.get(bank,true);var d=contracts.definition(bank,contract);if(d.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");return Map.of("version",b.version(),"items",jdbc.query("SELECT subject,payload,created_at FROM platform.loan_collection WHERE tenant_id=? AND contract_id=? ORDER BY created_at DESC LIMIT 100",(r,n)->Map.of("subject",r.getString(1),"entry",JSON.readValue(r.getString(2),LoanServicingModels.Collection.class),"at",r.getTimestamp(3).toInstant()),bank,contract));});}
 public void collection(Actor actor,UUID bank,UUID key,LoanServicingModels.Collection input){AccessService.admin(actor);tx.executeWithoutResult(t->{
  access.requireAssignable(actor.issuer(),actor.subject());jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},actor.issuer()+":"+actor.subject()+":"+key);var b=tenants.get(bank,true);
  var old=jdbc.queryForList("SELECT tenant_id,payload=?::jsonb AS same FROM platform.loan_collection WHERE issuer=? AND subject=? AND idempotency_key=?",JSON.writeValueAsString(input),actor.issuer(),actor.subject(),key);if(!old.isEmpty()){if(!bank.equals(old.getFirst().get("tenant_id"))||!Boolean.TRUE.equals(old.getFirst().get("same")))throw error("IDEMPOTENCY_CONFLICT");return;}
  if(b.version()!=input.expectedVersion()||b.status()!=Status.ACTIVE)throw error("STALE_VERSION");var d=contracts.definition(bank,input.contractId());if(d.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");access.requireAssignable(actor.issuer(),input.owner());
  jdbc.update("INSERT INTO platform.loan_collection(tenant_id,contract_id,issuer,subject,idempotency_key,payload) VALUES (?,?,?,?,?,?::jsonb)",bank,d.id(),actor.issuer(),actor.subject(),key,JSON.writeValueAsString(input));tenants.change(b,b.status(),b.displayName(),b.defaultLanguage());tenants.audit(actor,bank,"LOAN_COLLECTION_RECORDED",b.version()+1,b.status(),b.status(),input.note(),null,null,Map.of("contractId",d.id(),"evidenceReference",input.evidenceReference()));
 });}
}
