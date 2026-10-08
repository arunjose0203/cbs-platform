package com.cbs.contracts;
import static com.cbs.contracts.ContractModels.*;
import com.cbs.access.*;
import com.cbs.accounts.BankOperationStore;
import com.cbs.accounting.*;
import com.cbs.credit.CreditService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LoanFeeService {
 private final LendingAccess lending;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ContractService contracts;private final BankContractStore store;private final BankOperationStore customers;private final CreditService credit;private final TransactionTemplate tx;
 public LoanFeeService(LendingAccess lending,JdbcTemplate jdbc,TenantRepository tenants,ContractService contracts,BankContractStore store,BankOperationStore customers,CreditService credit,PlatformTransactionManager manager){this.lending=lending;this.jdbc=jdbc;this.tenants=tenants;this.contracts=contracts;this.store=store;this.customers=customers;this.credit=credit;tx=new TransactionTemplate(manager);}
 private static TenantException error(String code){return new TenantException(409,code,"Loan fee requires attention");}
 public static BigDecimal outstanding(JdbcTemplate jdbc,UUID contract){return jdbc.queryForObject("SELECT coalesce(sum((x.payload->'feeServicing'->>'after')::numeric),0) FROM (SELECT DISTINCT ON (payload->'feeServicing'->>'feeId') payload FROM platform.contract_delivery WHERE contract_id=? AND completed AND payload->'feeServicing' IS NOT NULL ORDER BY payload->'feeServicing'->>'feeId',created_at DESC,id DESC) x",BigDecimal.class,contract);}
 public Posting preview(Actor actor,UUID bank,LoanFeeModels.Change i){return tx.execute(t->{var b=tenants.get(bank,true);lending.maker(actor,bank,lending.scope(bank,com.cbs.approvals.ApprovalModels.Action.LOAN_FEE,i));return validate(b,i,UUID.randomUUID());});}
 public Posting validate(Tenant b,LoanFeeModels.Change i,UUID id){
  contracts.ready(b,i.expectedVersion(),i.businessDate());var original=contracts.definition(b.id(),i.contractId());if(original.terms().type()!=Type.LOAN)throw error("LOAN_REQUIRED");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE contract_id=? AND NOT completed)",Boolean.class,i.contractId()))throw error("CONTRACT_PENDING");
  var before=store.position(b,i.contractId());if(before.closed())throw error("CONTRACT_CLOSED");var a=contracts.account(b,original.account().id(),i.operation()==LoanFeeModels.Operation.FEE_PAY);var d=new Definition(original.id(),b.id(),a,original.terms());
  var amount=decimal(i.amount());if(amount.signum()<=0||amount.stripTrailingZeros().scale()>scale(d))throw error("LOAN_FEE_INVALID");
  UUID feeId;UUID receivable;UUID income;java.time.LocalDate due;BigDecimal old=BigDecimal.ZERO,next;var kind=Kind.valueOf(i.operation().name());
  if(kind==Kind.FEE_ASSESS){
   if(i.feeId()!=null||i.dueDate()==null||i.dueDate().isBefore(i.businessDate())||i.dueDate().isAfter(i.businessDate().plusYears(10))||i.receivableLedger()==null||i.incomeLedger()==null||i.receivableLedger().equals(i.incomeLedger()))throw error("LOAN_FEE_INVALID");
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract_delivery WHERE tenant_id=? AND kind='FEE_ASSESS' AND payload->'feeServicing'->'change'->>'evidenceReference'=?)",Boolean.class,b.id(),i.evidenceReference()))throw error("LOAN_FEE_REFERENCE_USED");
   var books=jdbc.queryForList("SELECT receivable_ledger,income_ledger FROM platform.loan_fee_book WHERE contract_id=?",d.id());
   if(books.isEmpty()){contracts.ledger(b,a,i.receivableLedger(),"ASSET",true);contracts.ledger(b,a,i.incomeLedger(),"INCOME",false);if(!jdbc.queryForObject("SELECT c.normal_balance='DEBIT' FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=?",Boolean.class,i.receivableLedger()))throw error("LOAN_FEE_LEDGER_INVALID");}else if(!books.getFirst().get("receivable_ledger").equals(i.receivableLedger())||!books.getFirst().get("income_ledger").equals(i.incomeLedger()))throw error("LOAN_FEE_LEDGER_INVALID");
   feeId=id;receivable=i.receivableLedger();income=i.incomeLedger();due=i.dueDate();next=amount;
  }else{
   if(i.feeId()==null||i.dueDate()!=null||i.receivableLedger()!=null||i.incomeLedger()!=null)throw error("LOAN_FEE_INVALID");
   var rows=jdbc.queryForList("SELECT payload::text FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed AND payload->'feeServicing'->>'feeId'=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,b.id(),d.id(),i.feeId().toString());if(rows.isEmpty())throw error("LOAN_FEE_NOT_FOUND");
   var f=JSON.readValue(rows.getFirst(),Posting.class).feeServicing();old=decimal(f.after());if(amount.compareTo(old)>0)throw error("LOAN_FEE_INVALID");next=old.subtract(amount);feeId=f.feeId();receivable=f.receivableLedger();income=f.incomeLedger();due=f.dueDate();
  }
  var position=customers.position(b,a);if(!position.reconciled())throw error("FUNDING_UNRECONCILED");String movement=kind==Kind.FEE_PAY?text(amount.negate()):"0";
  if(kind==Kind.FEE_PAY&&decimal(position.withdrawable()).compareTo(amount)<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
  var lines=kind==Kind.FEE_ASSESS?List.of(new JournalModels.Line(receivable,JournalModels.Side.DEBIT,i.amount()),new JournalModels.Line(income,JournalModels.Side.CREDIT,i.amount())):List.of(new JournalModels.Line(kind==Kind.FEE_PAY?position.ledgerId():income,JournalModels.Side.DEBIT,i.amount()),new JournalModels.Line(receivable,JournalModels.Side.CREDIT,i.amount()));
  boolean closed=kind!=Kind.FEE_ASSESS&&decimal(before.principal()).signum()==0&&decimal(before.interest()).signum()==0&&outstanding(jdbc,d.id()).subtract(amount).signum()==0;
  var after=new Position(before.principal(),before.interest(),before.carry(),before.accruedThrough(),closed);
  TransactionLimits.check(jdbc,b.id(),a.terms().currency(),"LOAN_"+kind.name(),i.businessDate(),amount);
  var p=contracts.posting(b,id,d,kind,i.businessDate(),before,after,movement,"0",lines,i.reason());return new Posting(p.id(),p.contract(),p.kind(),p.businessDate(),p.before(),p.after(),p.customerMovement(),p.tax(),p.journal(),p.reason(),null,null,null,new LoanFeeModels.Details(i,feeId,due,receivable,income,text(old),text(next)));
 }
 public void apply(Actor actor,UUID bank,LoanFeeModels.Change input,UUID id,String maker){var b=tenants.get(bank,true);lending.checker(actor,bank,lending.scope(bank,com.cbs.approvals.ApprovalModels.Action.LOAN_FEE,input),id,false);var p=validate(b,input,id);credit.requireReviewer(actor,b,p.contract().account().terms().currency(),decimal(input.amount()));contracts.save(actor,b,p,maker);}
}
