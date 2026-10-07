package com.cbs.cash;

import static com.cbs.cash.CashModels.*;
import static com.cbs.access.AccessModels.Permission.*;
import com.cbs.access.*;
import com.cbs.accounting.*;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CashService {
 private final com.cbs.accounts.FundingService funding;private final com.cbs.accounts.OperationService operations;private final BranchOperations accountBranches;private final com.cbs.customers.CustomerService customers;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final BranchService branches;private final AccessService access;
 private final BankCashStore store;private final JournalService journals;private final ReferenceDataService reference;private final TransactionTemplate tx;
 public CashService(com.cbs.accounts.FundingService funding,com.cbs.accounts.OperationService operations,BranchOperations accountBranches,com.cbs.customers.CustomerService customers,JdbcTemplate jdbc,TenantRepository tenants,BranchService branches,AccessService access,BankCashStore store,JournalService journals,ReferenceDataService reference,PlatformTransactionManager manager){this.funding=funding;this.operations=operations;this.accountBranches=accountBranches;this.customers=customers;this.jdbc=jdbc;this.tenants=tenants;this.branches=branches;this.access=access;this.store=store;this.journals=journals;this.reference=reference;this.tx=new TransactionTemplate(manager);}
 public void authorize(Actor actor,UUID bank,Change input,boolean review){
  if(input.kind()==Kind.REGISTER)AccessService.admin(actor);
  else branches.check(actor,bank,input.branchId(),review||adjustment(input.kind())||input.kind()==Kind.RECEIVE||input.kind()==Kind.DISPATCH||input.kind()==Kind.COUNT?APPROVE_BRANCH:CASH_TRANSACT);
 }
 public void readRequest(Actor actor,UUID request,boolean review,boolean cancel){
  var rows=jdbc.query("SELECT bank_id,payload FROM platform.approval_request WHERE id=? AND issuer=? AND action='CASH_OPERATION'",(r,n)->Map.entry(r.getObject(1,UUID.class),JSON.readValue(r.getString(2),Change.class)),request,actor.issuer());
  if(rows.size()!=1)throw error("APPROVAL_NOT_FOUND");var r=rows.getFirst();
  if(r.getValue().kind()==Kind.REGISTER)AccessService.admin(actor);
  else if(review)authorize(actor,r.getKey(),r.getValue(),!cancel);
  else branches.check(actor,r.getKey(),r.getValue().branchId(),READ_BRANCH);
 }
 public Location location(UUID bank,UUID id){
  var rows=jdbc.query("SELECT * FROM platform.cash_location WHERE tenant_id=? AND id=? AND applied",(r,n)->new Location(r.getObject("id",UUID.class),bank,r.getObject("branch_id",UUID.class),r.getObject("ledger_id",UUID.class),r.getString("code"),r.getString("name"),r.getString("currency"),Type.valueOf(r.getString("kind")),r.getObject("legal_entity_id",UUID.class)),bank,id);
  if(rows.size()!=1)throw error("CASH_LOCATION_NOT_READY");return rows.getFirst();
 }
 public Map<String,Object> dashboard(Actor actor,UUID bankId,UUID branch,int limit,int offset){
  branches.check(actor,bankId,branch,READ_BRANCH);AccessService.page(limit,offset);
  return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);boolean ready=store.ready(bank);
   var ids=jdbc.queryForList("SELECT id FROM platform.cash_location WHERE tenant_id=? AND branch_id=? AND applied ORDER BY code LIMIT ? OFFSET ?",UUID.class,bankId,branch,limit,offset);
   var positions=ready?ids.stream().map(id->store.position(bank,location(bankId,id))).toList():List.of();
   return Map.of("version",bank.version(),"businessDate",reference.read(bank).businessDate(),"storageReady",ready,"items",positions,"limit",limit,"offset",offset,"accounts",jdbc.queryForList("SELECT a.id,a.account_number,a.currency,a.state,l.ledger_id AS liability_ledger_id FROM platform.account_registry a JOIN platform.account_branch b ON b.account_id=a.id AND b.tenant_id=a.tenant_id LEFT JOIN platform.account_ledger l ON l.account_id=a.id WHERE b.tenant_id=? AND b.branch_id=? AND a.delivery='APPLIED' AND a.state IN ('AWAITING_FUNDING','ACTIVE') ORDER BY a.account_number LIMIT 100",bankId,branch),"requests",jdbc.queryForList("SELECT id,status,maker_subject,reason,proposed_values,created_at FROM platform.approval_request WHERE bank_id=? AND issuer=? AND action='CASH_OPERATION' AND payload->>'kind'<>'REGISTER' AND payload->>'branchId'=? ORDER BY created_at DESC,id LIMIT 25",bankId,actor.issuer(),branch.toString()),"deliveries",jdbc.queryForList("SELECT request_id,completed,failure_code FROM platform.cash_delivery WHERE tenant_id=? AND branch_id=? ORDER BY created_at DESC,request_id LIMIT 25",bankId,branch));
  });
 }
 public List<Map<String,Object>> exceptions(Actor actor,UUID bank,UUID branch){
  branches.check(actor,bank,branch,READ_BRANCH);
  return jdbc.queryForList("SELECT e.id,e.location_id,l.code,e.currency,e.business_date,e.book_amount::text,e.counted_amount::text,(e.counted_amount-e.book_amount)::text AS difference,e.reporter,e.payload,e.created_at,r.reviewer,r.payload AS resolution,r.created_at AS resolved_at,r.adjustment_request_id,a.request_id AS pending_adjustment FROM platform.cash_exception e JOIN platform.cash_location l ON l.id=e.location_id LEFT JOIN platform.cash_exception_resolution r ON r.case_id=e.id LEFT JOIN platform.cash_adjustment a ON a.case_id=e.id WHERE e.tenant_id=? AND e.branch_id=? AND e.issuer=? ORDER BY (r.case_id IS NULL) DESC,e.created_at DESC,e.id LIMIT 100",bank,branch,actor.issuer());
 }
 private void requireNoException(UUID bank,UUID location){
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_exception e WHERE e.tenant_id=? AND e.location_id=? AND NOT EXISTS(SELECT 1 FROM platform.cash_exception_resolution r WHERE r.case_id=e.id))",Boolean.class,bank,location))throw error("CASH_EXCEPTION_OPEN");
 }
 public UUID reportException(Actor actor,UUID bankId,UUID branch,UUID key,CashExceptionInput input){
  return tx.execute(t->{
   var bank=tenants.get(bankId,true);branches.check(actor,bankId,branch,CASH_TRANSACT);
   var replay=jdbc.queryForList("SELECT id FROM platform.cash_exception WHERE id=? AND tenant_id=? AND branch_id=? AND issuer=? AND reporter=? AND payload=?::jsonb",UUID.class,key,bankId,branch,actor.issuer(),actor.subject(),JSON.writeValueAsString(input));
   if(!replay.isEmpty())return key;
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_exception WHERE id=?)",Boolean.class,key))throw error("IDEMPOTENCY_CONFLICT");
   var p=exceptionPosition(bank,branch,input);requireNoException(bankId,input.locationId());
   var count=counted(p.location().currency(),input.counts());var book=new BigDecimal(p.balance());
   if(count.compareTo(book)==0)throw error("CASH_EXCEPTION_NOT_REQUIRED");
   jdbc.update("INSERT INTO platform.cash_exception(id,tenant_id,branch_id,location_id,issuer,reporter,currency,business_date,book_amount,counted_amount,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)",key,bankId,branch,input.locationId(),actor.issuer(),actor.subject(),p.location().currency(),input.businessDate(),book,count,JSON.writeValueAsString(input));
   exceptionAudit(actor,bank,"CASH_EXCEPTION_REPORTED",key,input);
   return key;
  });
 }
 public UUID resolveException(Actor actor,UUID bankId,UUID branch,UUID id,CashExceptionInput input){
  return tx.execute(t->{
   var bank=tenants.get(bankId,true);branches.check(actor,bankId,branch,APPROVE_BRANCH);
   var rows=jdbc.queryForList("SELECT reporter,location_id FROM platform.cash_exception WHERE id=? AND tenant_id=? AND branch_id=? AND issuer=?",id,bankId,branch,actor.issuer());
   if(rows.size()!=1)throw error("CASH_EXCEPTION_NOT_FOUND");var row=rows.getFirst();
   if(!input.locationId().equals(row.get("location_id")))throw error("CASH_SCOPE_MISMATCH");
   if(actor.subject().equals(row.get("reporter")))throw error("SELF_APPROVAL_FORBIDDEN");
   var replay=jdbc.queryForList("SELECT case_id FROM platform.cash_exception_resolution WHERE case_id=? AND issuer=? AND reviewer=? AND payload=?::jsonb",UUID.class,id,actor.issuer(),actor.subject(),JSON.writeValueAsString(input));
   if(!replay.isEmpty())return id;
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_exception_resolution WHERE case_id=?)",Boolean.class,id))throw error("CASH_EXCEPTION_RESOLVED");
   var p=exceptionPosition(bank,branch,input);
   if(counted(p.location().currency(),input.counts()).compareTo(new BigDecimal(p.balance()))!=0)throw error("CASH_COUNT_MISMATCH");
   jdbc.update("INSERT INTO platform.cash_exception_resolution(case_id,issuer,reviewer,payload) VALUES (?,?,?,?::jsonb)",id,actor.issuer(),actor.subject(),JSON.writeValueAsString(input));
   exceptionAudit(actor,bank,"CASH_EXCEPTION_RECOUNT_VERIFIED",id,input);
   return id;
  });
 }
 private Position exceptionPosition(Tenant bank,UUID branch,CashExceptionInput input){
  DayCloseService.requireOpen(jdbc,bank.id());
  if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");
  if(!reference.read(bank).businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))throw error("CASH_DELIVERY_PENDING");
  var l=location(bank.id(),input.locationId());if(!l.branchId().equals(branch))throw error("BRANCH_ACCESS_DENIED");
  var p=store.position(bank,l);if(!p.reconciled())throw error("CASH_UNRECONCILED");return p;
 }
 private void exceptionAudit(Actor actor,Tenant bank,String action,UUID id,CashExceptionInput input){
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
  // A verified recount does not substitute for the existing independently approved daily count.
  tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("caseId",id,"locationId",input.locationId(),"counts",input.counts()));
 }
 public Entry validate(Actor maker,Tenant bank,Change input,UUID id){
  authorize(maker,bank.id(),input,false);DayCloseService.requireOpen(jdbc,bank.id());
  if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");
  if(!reference.read(bank).businessDate().equals(input.businessDate()))throw error("JOURNAL_BUSINESS_DATE_INVALID");
  if(!store.ready(bank))throw error("CASH_STORAGE_NOT_READY");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_delivery WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))throw error("CASH_DELIVERY_PENDING");
  var entities=jdbc.queryForList("SELECT legal_entity_id FROM platform.branch WHERE tenant_id=? AND id=? AND active",UUID.class,bank.id(),input.branchId());if(entities.size()!=1)throw error("BRANCH_NOT_OPERATIONAL");
  Location location,target=null;JournalModels.Entry journal=null;com.cbs.accounts.FundingModels.Entry deposit=null;com.cbs.accounts.OperationModels.Entry withdrawal=null;
  boolean reverse=input.kind()==Kind.REVERSAL;
  if(input.transit()!=null&&input.kind()!=Kind.RECEIVE&&input.kind()!=Kind.DISPATCH)throw error("CASH_TRANSIT_INVALID");
  if(reverse!=(input.originalId()!=null))throw error("CASH_FIELDS_INVALID");
  boolean adjust=adjustment(input.kind());
  if(!adjust&&input.exceptionId()!=null)throw error("CASH_FIELDS_INVALID");
  boolean customer=input.kind()==Kind.DEPOSIT||input.kind()==Kind.WITHDRAWAL;
  if(!customer&&(input.accountId()!=null||input.liabilityLedgerId()!=null))throw error("CASH_FIELDS_INVALID");
  if(input.kind()==Kind.REGISTER){
   if(input.locationId()!=null||input.destinationId()!=null||input.amount()!=null||input.counts()!=null||input.ledgerId()==null||input.type()==null||input.code()==null||input.name()==null||input.name().isBlank()||input.currency()==null)throw error("CASH_FIELDS_INVALID");
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location WHERE tenant_id=? AND code=?) OR EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?)",Boolean.class,bank.id(),input.code(),input.ledgerId()))throw error("CASH_LEDGER_USED");
   if(transitLedger(input.ledgerId()))throw error("CASH_TRANSIT_MANAGED");
   asset(bank,input.ledgerId(),entities.getFirst(),input.currency());
   if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId(),input.ledgerId()))throw error("CASH_LEDGER_USED");
   location=new Location(id,bank.id(),input.branchId(),input.ledgerId(),input.code(),input.name().trim(),input.currency(),input.type(),entities.getFirst());
  }else{
   if(input.locationId()==null||input.code()!=null||input.name()!=null||input.currency()!=null||input.type()!=null)throw error("CASH_FIELDS_INVALID");
   location=location(bank.id(),input.locationId());if(!adjust)requireNoException(bank.id(),location.id());if(!location.branchId().equals(input.branchId()))throw error("BRANCH_ACCESS_DENIED");
   if(monetary(input.kind())){
    if((!adjust&&!(input.transit()!=null&&input.kind()==Kind.RECEIVE)&&input.counts()!=null)||input.amount()==null||!input.amount().matches("[0-9]{1,18}(\\.[0-9]{1,6})?")||new BigDecimal(input.amount()).signum()<=0)throw error("CASH_FIELDS_INVALID");
    if(reverse){
     var original=original(bank.id(),input.originalId(),maker.issuer());
     if(!original.location().equals(location)||!Objects.equals(input.destinationId(),original.destination()==null?null:original.destination().id())||input.ledgerId()!=null||new BigDecimal(input.amount()).compareTo(new BigDecimal(original.change().amount()))!=0)throw error("CASH_REVERSAL_INVALID");
     target=original.destination();if(target!=null)requireNoException(bank.id(),target.id());
     if(original.change().kind()==Kind.RECEIVE||original.change().kind()==Kind.DISPATCH)branches.check(maker,bank.id(),input.branchId(),APPROVE_BRANCH);
     var operation=cashReversalOperation(bank,input,original);
     if(operation!=null){withdrawal=operations.validateCash(bank,operation,id);journal=withdrawal.journal();}
     else{
      var old=original.journal();var lines=old.lines().stream().map(l->new JournalModels.Line(l.ledgerId(),l.side()==JournalModels.Side.DEBIT?JournalModels.Side.CREDIT:JournalModels.Side.DEBIT,l.amount())).toList();
      journals.validateCash(bank,new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),old.legalEntityId(),old.currency(),lines,input.originalId(),input.reason()));
      journal=new JournalModels.Entry(id,bank.id(),input.reference().trim(),input.reason().trim(),input.businessDate(),old.legalEntityId(),old.currency(),lines,input.originalId());
     }
    }else if(adjust){
     validateAdjustment(maker,bank,input,location);
     UUID debit=input.kind()==Kind.OVERAGE?location.ledgerId():input.ledgerId();
     UUID credit=input.kind()==Kind.OVERAGE?input.ledgerId():location.ledgerId();
     var lines=List.of(new JournalModels.Line(debit,JournalModels.Side.DEBIT,input.amount()),new JournalModels.Line(credit,JournalModels.Side.CREDIT,input.amount()));
     journals.validateCash(bank,new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),location.legalEntityId(),location.currency(),lines,null,input.reason()));
     journal=new JournalModels.Entry(id,bank.id(),input.reference().trim(),input.reason().trim(),input.businessDate(),location.legalEntityId(),location.currency(),lines,null);
    }else if(customer){
     if(location.type()!=Type.TILL||input.accountId()==null||input.destinationId()!=null||input.ledgerId()!=null||!input.branchId().equals(accountBranches.branch(bank.id(),input.accountId())))throw error("CASH_SCOPE_MISMATCH");
     com.cbs.accounts.AccountModels.Definition account;
     if(input.kind()==Kind.DEPOSIT){if(input.liabilityLedgerId()==null)throw error("CASH_FIELDS_INVALID");deposit=funding.cashEntry(bank,fundingChange(input,location),id);journal=deposit.journal();account=deposit.account();}
     else{if(input.liabilityLedgerId()!=null)throw error("CASH_FIELDS_INVALID");withdrawal=operations.validateCash(bank,withdrawalChange(input,location),id);journal=withdrawal.journal();account=withdrawal.account();}
     if(!account.terms().currency().equals(location.currency())||!account.legalEntityId().equals(location.legalEntityId()))throw error("CASH_SCOPE_MISMATCH");
    }else {
    if(input.kind()==Kind.TRANSFER){
     if(input.destinationId()==null||input.ledgerId()!=null||input.locationId().equals(input.destinationId()))throw error("CASH_FIELDS_INVALID");
     target=location(bank.id(),input.destinationId());requireNoException(bank.id(),target.id());
     if(!target.branchId().equals(location.branchId())||!target.currency().equals(location.currency())||!target.legalEntityId().equals(location.legalEntityId())||(target.type()==Type.TILL&&location.type()==Type.TILL))throw error("CASH_SCOPE_MISMATCH");
    }else{
     if(input.destinationId()!=null||input.ledgerId()==null||location.type()!=Type.VAULT)throw error("CASH_FIELDS_INVALID");
     asset(bank,input.ledgerId(),location.legalEntityId(),location.currency());
     if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_location WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM platform.account_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId(),input.ledgerId()))throw error("CASH_LEDGER_USED");
    }
    UUID debit=input.kind()==Kind.RECEIVE?location.ledgerId():input.kind()==Kind.TRANSFER?target.ledgerId():input.ledgerId();
    UUID credit=input.kind()==Kind.RECEIVE?input.ledgerId():location.ledgerId();
    var lines=List.of(new JournalModels.Line(debit,JournalModels.Side.DEBIT,input.amount()),new JournalModels.Line(credit,JournalModels.Side.CREDIT,input.amount()));
    journals.validateCash(bank,new JournalModels.Change(input.expectedVersion(),input.reference(),input.reason(),input.businessDate(),location.legalEntityId(),location.currency(),lines,null,input.reason()));
    journal=new JournalModels.Entry(id,bank.id(),input.reference().trim(),input.reason().trim(),input.businessDate(),location.legalEntityId(),location.currency(),lines,null);
    }
    validateTransit(maker,bank,input,location);
    limits(maker,bank.id(),input,location.currency());
   }else if(input.kind()==Kind.OPEN||input.kind()==Kind.CLOSE||input.kind()==Kind.COUNT){
    if((input.kind()!=Kind.COUNT&&location.type()!=Type.TILL)||input.destinationId()!=null||input.ledgerId()!=null||input.amount()!=null)throw error("CASH_FIELDS_INVALID");counted(location.currency(),input.counts());
   }else throw error("CASH_FIELDS_INVALID");
  }
  if(reference.read(bank).currencies().stream().noneMatch(c->c.code().equals(location.currency())&&c.enabled()))throw error("CURRENCY_NOT_ENABLED");
  var e=new Entry(id,bank.id(),input,location,target,journal,deposit,withdrawal);store.validate(bank,e,maker.subject());return e;
 }
 private boolean transitLedger(UUID ledger){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_transit WHERE ledger_id=?)",Boolean.class,ledger);}
 private void validateTransit(Actor actor,Tenant bank,Change input,Location source){
  var transit=input.transit();
  if(transit==null){if(input.ledgerId()!=null&&transitLedger(input.ledgerId()))throw error("CASH_TRANSIT_MANAGED");return;}
  if(source.type()!=Type.VAULT||input.destinationId()!=null||input.ledgerId()==null)throw error("CASH_TRANSIT_INVALID");
  if(input.kind()==Kind.DISPATCH){
   if(transit.dispatchId()!=null||transit.destinationId()==null||transit.expectedDate()==null||transit.expectedDate().isBefore(input.businessDate())||transit.returned())throw error("CASH_TRANSIT_INVALID");
   var target=location(bank.id(),transit.destinationId());requireNoException(bank.id(),target.id());
   if(target.type()!=Type.VAULT||source.branchId().equals(target.branchId())||!source.currency().equals(target.currency())||!source.legalEntityId().equals(target.legalEntityId())||!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.branch WHERE tenant_id=? AND id=? AND active)",Boolean.class,bank.id(),target.branchId()))throw error("CASH_SCOPE_MISMATCH");
   if(!transitLedger(input.ledgerId())){
    if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.journal_ledger WHERE ledger_id=?)",Boolean.class,input.ledgerId()))throw error("CASH_LEDGER_USED");
    store.unused(bank,input.ledgerId());
   }
  }else{
   if(transit.dispatchId()==null||transit.destinationId()!=null||transit.expectedDate()!=null)throw error("CASH_TRANSIT_INVALID");
   var rows=jdbc.queryForList("SELECT t.*,c.payload->'change'->>'amount' AS amount FROM platform.cash_transit t JOIN platform.cash_delivery c ON c.request_id=t.dispatch_id WHERE t.dispatch_id=? AND t.tenant_id=? AND c.completed AND c.reviewer_issuer=?",transit.dispatchId(),bank.id(),actor.issuer());
   if(rows.size()!=1)throw error("CASH_TRANSIT_NOT_READY");var row=rows.getFirst();
   if(!input.locationId().equals(row.get(transit.returned()?"source_id":"destination_id"))||!input.ledgerId().equals(row.get("ledger_id")))throw error("CASH_SCOPE_MISMATCH");
   BigDecimal used=jdbc.queryForObject("SELECT coalesce(sum((payload->'change'->>'amount')::numeric),0) FROM platform.cash_delivery WHERE tenant_id=? AND payload->'change'->'transit'->>'dispatchId'=?",BigDecimal.class,bank.id(),transit.dispatchId().toString());
   if(new BigDecimal((String)row.get("amount")).subtract(used).compareTo(new BigDecimal(input.amount()))<0)throw error("CASH_TRANSIT_AMOUNT_EXCEEDED");
   if(counted(source.currency(),input.counts()).compareTo(new BigDecimal(input.amount()))!=0)throw error("CASH_COUNT_MISMATCH");
  }
 }
 public List<Map<String,Object>> transitDestinations(Actor actor,UUID bank,UUID branch){
  branches.check(actor,bank,branch,APPROVE_BRANCH);
  return jdbc.queryForList("SELECT l.id,l.code,l.currency,l.legal_entity_id,b.display_name AS branch_name FROM platform.cash_location l JOIN platform.branch b ON b.id=l.branch_id AND b.tenant_id=l.tenant_id WHERE l.tenant_id=? AND l.branch_id<>? AND l.applied AND l.kind='VAULT' AND b.active ORDER BY b.display_name,l.code LIMIT 100",bank,branch);
 }
 public List<Map<String,Object>> transit(Actor actor,UUID bank,UUID branch){
  branches.check(actor,bank,branch,READ_BRANCH);
  return jdbc.queryForList("SELECT t.dispatch_id,t.source_id,t.destination_id,t.ledger_id,t.expected_date,s.branch_id AS source_branch,d.branch_id AS destination_branch,s.code AS source_code,d.code AS destination_code,s.currency,c.completed AS dispatched,c.payload->'change'->>'reference' AS reference,c.payload->'change'->>'amount' AS amount,((c.payload->'change'->>'amount')::numeric-coalesce(r.posted,0))::text AS outstanding,((c.payload->'change'->>'amount')::numeric-coalesce(r.reserved,0))::text AS receivable,coalesce(r.returned,0)::text AS returned,coalesce(r.received,0)::text AS received,(t.expected_date<greatest(bs.business_date,(CURRENT_TIMESTAMP AT TIME ZONE bs.business_timezone)::date)) AS overdue FROM platform.cash_transit t JOIN platform.cash_location s ON s.id=t.source_id JOIN platform.cash_location d ON d.id=t.destination_id JOIN platform.cash_delivery c ON c.request_id=t.dispatch_id JOIN platform.bank_settings bs ON bs.tenant_id=t.tenant_id LEFT JOIN LATERAL (SELECT sum((x.payload->'change'->>'amount')::numeric) AS reserved,sum((x.payload->'change'->>'amount')::numeric) FILTER(WHERE x.completed) AS posted,sum((x.payload->'change'->>'amount')::numeric) FILTER(WHERE x.completed AND (x.payload->'change'->'transit'->>'returned')::boolean) AS returned,sum((x.payload->'change'->>'amount')::numeric) FILTER(WHERE x.completed AND NOT (x.payload->'change'->'transit'->>'returned')::boolean) AS received FROM platform.cash_delivery x WHERE x.tenant_id=t.tenant_id AND x.payload->'change'->'transit'->>'dispatchId'=t.dispatch_id::text) r ON true WHERE t.tenant_id=? AND c.reviewer_issuer=? AND (s.branch_id=? OR d.branch_id=?) ORDER BY (coalesce(r.posted,0)<(c.payload->'change'->>'amount')::numeric) DESC,c.created_at DESC LIMIT 100",bank,actor.issuer(),branch,branch);
 }
 private Entry original(UUID bank,UUID id,String issuer){
  var rows=jdbc.query("SELECT payload FROM platform.cash_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=? AND completed",(r,n)->JSON.readValue(r.getString(1),Entry.class),id,bank,issuer);
  if(rows.size()!=1||rows.getFirst().change().transit()!=null||!Set.of(Kind.RECEIVE,Kind.DISPATCH,Kind.TRANSFER,Kind.DEPOSIT,Kind.WITHDRAWAL).contains(rows.getFirst().change().kind()))throw error("CASH_REVERSAL_INVALID");return rows.getFirst();
 }
 private com.cbs.accounts.OperationModels.Change cashReversalOperation(Tenant bank,Change input,Entry original){
  if(original.funding()==null&&original.operation()==null)return null;
  UUID account=original.funding()!=null?original.funding().accountId():original.operation().account().id();
  if(!input.branchId().equals(accountBranches.branch(bank.id(),account)))throw error("CASH_SCOPE_MISMATCH");
  var kind=original.funding()!=null?com.cbs.accounts.OperationModels.Kind.FUNDING_REVERSAL:com.cbs.accounts.OperationModels.Kind.WITHDRAWAL_REVERSAL;
  return new com.cbs.accounts.OperationModels.Change(input.expectedVersion(),kind,account,null,null,input.originalId(),input.amount(),input.businessDate(),input.reference(),input.reason());
 }
 public List<Map<String,Object>> reversible(Actor actor,UUID bank,UUID branch){
  branches.check(actor,bank,branch,READ_BRANCH);
  return jdbc.queryForList("SELECT c.request_id,c.payload->'change'->>'reference' AS reference,c.payload->'change'->>'kind' AS kind,c.payload->'change'->>'amount' AS amount,c.payload->'location'->>'currency' AS currency,c.payload->'location'->>'id' AS location_id,c.payload->'destination'->>'id' AS destination_id FROM platform.cash_delivery c WHERE c.tenant_id=? AND c.branch_id=? AND c.reviewer_issuer=? AND c.completed AND c.payload->'change'->'transit' IS NULL AND c.payload->'change'->>'kind' IN ('RECEIVE','DISPATCH','TRANSFER','DEPOSIT','WITHDRAWAL') AND NOT EXISTS(SELECT 1 FROM platform.journal_delivery j WHERE j.reversal_of=c.request_id) ORDER BY c.completed_at DESC,c.request_id LIMIT 100",bank,branch,actor.issuer());
 }
 public List<Map<String,Object>> adjustmentLedgers(Actor actor,UUID bank,UUID branch,UUID locationId){
  branches.check(actor,bank,branch,READ_BRANCH);var l=location(bank,locationId);
  if(!branch.equals(l.branchId()))throw error("BRANCH_ACCESS_DENIED");
  return jdbc.queryForList("SELECT l.id,l.code,d.payload->>'name' AS name,c.category FROM platform.ledger_registry l JOIN platform.ledger_delivery d ON d.request_id=l.request_id JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND ((c.category='EXPENSE' AND c.normal_balance='DEBIT') OR (c.category='INCOME' AND c.normal_balance='CREDIT')) ORDER BY l.code LIMIT 100",bank,l.legalEntityId(),l.currency());
 }
 private String validateAdjustment(Actor actor,Tenant bank,Change input,Location location){
  if(input.exceptionId()==null||input.destinationId()!=null||input.ledgerId()==null)throw error("CASH_FIELDS_INVALID");
  var rows=jdbc.queryForList("SELECT e.reporter,e.book_amount,e.counted_amount FROM platform.cash_exception e WHERE e.id=? AND e.tenant_id=? AND e.branch_id=? AND e.location_id=? AND e.issuer=? AND e.business_date=? AND NOT EXISTS(SELECT 1 FROM platform.cash_exception_resolution r WHERE r.case_id=e.id) AND NOT EXISTS(SELECT 1 FROM platform.cash_adjustment a WHERE a.case_id=e.id)",input.exceptionId(),bank.id(),input.branchId(),location.id(),actor.issuer(),input.businessDate());
  if(rows.size()!=1)throw error("CASH_ADJUSTMENT_UNAVAILABLE");var row=rows.getFirst();
  var book=(BigDecimal)row.get("book_amount");var physical=(BigDecimal)row.get("counted_amount");var difference=physical.subtract(book);
  if((input.kind()==Kind.OVERAGE)!=(difference.signum()>0)||difference.abs().compareTo(new BigDecimal(input.amount()))!=0||physical.compareTo(counted(location.currency(),input.counts()))!=0||book.compareTo(new BigDecimal(store.position(bank,location).balance()))!=0)throw error("CASH_ADJUSTMENT_MISMATCH");
  String category=input.kind()==Kind.SHORTAGE?"EXPENSE":"INCOME",normal=input.kind()==Kind.SHORTAGE?"DEBIT":"CREDIT";
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND c.category=? AND c.normal_balance=?)",Boolean.class,input.ledgerId(),bank.id(),location.legalEntityId(),location.currency(),category,normal))throw error("CASH_ADJUSTMENT_LEDGER_INVALID");
  return (String)row.get("reporter");
 }
 private com.cbs.accounts.FundingModels.Change fundingChange(Change input,Location l){return new com.cbs.accounts.FundingModels.Change(input.expectedVersion(),input.accountId(),l.ledgerId(),input.liabilityLedgerId(),input.amount(),input.businessDate(),input.reference(),input.reason());}
 private com.cbs.accounts.OperationModels.Change withdrawalChange(Change input,Location l){return new com.cbs.accounts.OperationModels.Change(input.expectedVersion(),com.cbs.accounts.OperationModels.Kind.WITHDRAWAL,input.accountId(),null,l.ledgerId(),null,input.amount(),input.businessDate(),input.reference(),input.reason());}
 public void record(Actor maker,UUID bank,Change input,UUID id){
  if(input.kind()==Kind.WITHDRAWAL)accountBranches.record(maker,bank,withdrawalChange(input,location(bank,input.locationId())),id);
  if(input.kind()==Kind.REVERSAL){var operation=cashReversalOperation(tenants.get(bank,false),input,original(bank,input.originalId(),maker.issuer()));if(operation!=null)accountBranches.record(maker,bank,operation,id);}
 }
 private void asset(Tenant bank,UUID id,UUID entity,String currency){
  if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.ledger_registry l JOIN platform.chart_account c ON c.id=l.chart_account_id WHERE l.id=? AND l.tenant_id=? AND l.legal_entity_id=? AND l.currency=? AND l.active AND l.delivery='APPLIED' AND c.active AND c.kind='POSTING' AND c.category='ASSET' AND c.normal_balance='DEBIT')",Boolean.class,id,bank.id(),entity,currency))throw error("FUNDING_SOURCE_INVALID");
 }
 private void limits(Actor actor,UUID bank,Change input,String currency){
  String operation="CASH_"+input.kind().name();var amount=new BigDecimal(input.amount());TransactionLimits.check(jdbc,bank,currency,operation,input.businessDate(),amount);
  UUID staff=access.requireAssignable(actor.issuer(),actor.subject()).id();
  for(UUID scope:List.of(input.branchId(),staff)){
   var policies=jdbc.query("SELECT per_transaction,daily_total FROM platform.branch_transaction_limit WHERE tenant_id=? AND branch_id=? AND scope_id=? AND currency=? AND operation=?",(r,n)->new BigDecimal[]{r.getBigDecimal(1),r.getBigDecimal(2)},bank,input.branchId(),scope,currency,operation);
   if(policies.isEmpty())throw error("BRANCH_LIMIT_REQUIRED");
   var args=new ArrayList<Object>(List.of(bank,input.branchId(),currency,input.kind().name(),input.businessDate().toString()));if(scope.equals(staff))args.add(staff);
   BigDecimal used=jdbc.queryForObject("SELECT coalesce(sum((payload->'change'->>'amount')::numeric),0) FROM platform.cash_delivery WHERE tenant_id=? AND branch_id=? AND payload->'location'->>'currency'=? AND payload->'change'->>'kind'=? AND payload->'change'->>'businessDate'=?"+(scope.equals(staff)?" AND staff_id=?":""),BigDecimal.class,args.toArray());
   TransactionLimits.enforce(policies.getFirst()[0],policies.getFirst()[1],used,amount);
  }
 }
 public Map<String,Object> values(Entry e){var m=new LinkedHashMap<String,Object>();m.put("operation",e.change().kind());if(e.change().transit()!=null)m.put("transit",e.change().transit());m.put("branchId",e.location().branchId());m.put("location",e.location().code());m.put("locationId",e.location().id());m.put("locationName",e.location().name());m.put("locationType",e.location().type());m.put("cashLedgerId",e.location().ledgerId());m.put("legalEntityId",e.location().legalEntityId());if(e.change().originalId()!=null){m.put("originalId",e.change().originalId());m.put("cashDelta",cashDelta(e,e.location()));if(e.destination()!=null)m.put("destinationCashDelta",cashDelta(e,e.destination()));if(e.operation()!=null)m.put("customerOperation",e.operation().change().kind());}if(e.change().exceptionId()!=null)m.put("exceptionId",e.change().exceptionId());if(e.change().ledgerId()!=null)m.put("selectedLedgerId",e.change().ledgerId());m.put("currency",e.location().currency());m.put("businessDate",e.change().businessDate());m.put("reference",e.change().reference());if(e.funding()!=null)m.put("customerAccount",e.funding().account().accountNumber());if(e.operation()!=null)m.put("customerAccount",e.operation().account().accountNumber());if(e.change().amount()!=null)m.put("amount",e.change().amount());if(e.destination()!=null)m.put("destination",e.destination().code());if(e.change().counts()!=null){m.put("counts",e.change().counts());m.put("countedTotal",counted(e.location().currency(),e.change().counts()).toPlainString());}return m;}
 private String cashDelta(Entry e,Location l){return e.journal().lines().stream().filter(x->x.ledgerId().equals(l.ledgerId())).map(x->new BigDecimal(x.amount()).multiply(x.side()==JournalModels.Side.DEBIT?BigDecimal.ONE:BigDecimal.ONE.negate())).reduce(BigDecimal.ZERO,BigDecimal::add).stripTrailingZeros().toPlainString();}
 public Map<String,Object> before(Tenant bank,Entry e){if(e.change().kind()==Kind.REGISTER)return Map.of();var p=store.position(bank,e.location());return Map.of("cashBalance",p.balance(),"ledgerBalance",p.ledgerBalance(),"sessionOpen",p.sessionOpen(),"custodian",p.custodian()==null?"":p.custodian());}
 public void enqueue(Actor reviewer,UUID bankId,Change input,UUID id,String maker){
  var bank=tenants.get(bankId,true);authorize(reviewer,bankId,input,true);
  var makerActor=new Actor(reviewer.issuer(),maker,input.kind()==Kind.REGISTER,reviewer.correlationId());var e=validate(makerActor,bank,input,id);
  if(adjustment(input.kind())){
   if(reviewer.subject().equals(validateAdjustment(makerActor,bank,input,e.location())))throw error("SELF_APPROVAL_FORBIDDEN");
   jdbc.update("INSERT INTO platform.cash_adjustment(case_id,request_id) VALUES (?,?)",input.exceptionId(),id);
  }
  if(input.kind()==Kind.REGISTER){var l=e.location();jdbc.update("INSERT INTO platform.cash_location(id,tenant_id,branch_id,ledger_id,code,name,currency,kind,legal_entity_id,request_id) VALUES (?,?,?,?,?,?,?,?,?,?)",l.id(),bankId,l.branchId(),l.ledgerId(),l.code(),l.name(),l.currency(),l.type().name(),l.legalEntityId(),id);}
  if(e.funding()!=null)funding.enqueueCash(reviewer,bankId,fundingChange(input,e.location()),id,maker);
  if(e.operation()!=null)operations.enqueueCash(reviewer,bankId,e.operation().change(),id,maker);
  if(e.journal()!=null&&e.funding()==null&&e.operation()==null){var j=e.journal();jdbc.update("INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,reversal_of,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,j.reference(),j.reversalOf(),JSON.writeValueAsString(j),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());for(var line:j.lines())jdbc.update("INSERT INTO platform.journal_ledger(request_id,ledger_id) VALUES (?,?)",id,line.ledgerId());}
  jdbc.update("INSERT INTO platform.cash_delivery(request_id,tenant_id,branch_id,staff_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",id,bankId,input.branchId(),access.requireAssignable(reviewer.issuer(),maker).id(),JSON.writeValueAsString(e),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
  if(input.transit()!=null&&input.kind()==Kind.DISPATCH)jdbc.update("INSERT INTO platform.cash_transit(dispatch_id,tenant_id,source_id,destination_id,ledger_id,expected_date) VALUES (?,?,?,?,?,?)",id,bankId,input.locationId(),input.transit().destinationId(),input.ledgerId(),input.transit().expectedDate());
  if(e.funding()==null&&e.operation()==null)tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(reviewer,bankId,"CASH_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+id,null,null,values(e));
 }
 public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.cash_delivery WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
 public void deliver(UUID id){tx.executeWithoutResult(t->{
  var rows=jdbc.query("SELECT * FROM platform.cash_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(JSON.readValue(r.getString("payload"),Entry.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);if(rows.isEmpty())return;
  var job=rows.getFirst();var bank=tenants.get(job.entry().bankId(),true);String failure=null;
  try{if(!store.posted(bank,id)){if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");if(job.entry().funding()!=null)customers.requireEligible(bank,job.entry().funding().account().customerId());if(job.entry().operation()!=null)customers.requireEligible(bank,job.entry().operation().account().customerId());}store.post(bank,job);}catch(TenantException exception){failure=exception.code();}
  jdbc.update("UPDATE platform.cash_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);
  jdbc.update("UPDATE platform.journal_delivery SET failure_code=? WHERE request_id=?",failure,id);
  for(String table:List.of("funding_delivery","account_delivery","account_operation"))jdbc.update("UPDATE platform."+table+" SET failure_code=? WHERE request_id=?",failure,id);if(failure!=null)return;
  jdbc.update("UPDATE platform.cash_delivery SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",id);
  jdbc.update("UPDATE platform.journal_delivery SET completed=true,completed_at=clock_timestamp(),failure_code=NULL WHERE request_id=?",id);
  if(job.entry().funding()!=null){for(String table:List.of("funding_delivery","account_delivery"))jdbc.update("UPDATE platform."+table+" SET completed=true,completed_at=clock_timestamp(),failure_code=NULL WHERE request_id=?",id);jdbc.update("UPDATE platform.account_registry SET delivery='APPLIED' WHERE id=? AND request_id=?",job.entry().funding().accountId(),id);}
  if(job.entry().operation()!=null)jdbc.update("UPDATE platform.account_operation SET completed=true,completed_at=clock_timestamp(),failure_code=NULL WHERE request_id=?",id);
  var input=job.entry().change();
  if(adjustment(input.kind()))jdbc.update("INSERT INTO platform.cash_exception_resolution(case_id,issuer,reviewer,payload,adjustment_request_id) VALUES (?,?,?,?::jsonb,?)",input.exceptionId(),job.issuer(),job.checker(),JSON.writeValueAsString(input),id);
  if(input.kind()==Kind.REGISTER)jdbc.update("UPDATE platform.cash_location SET applied=true WHERE id=?",job.entry().location().id());
  if(input.kind()==Kind.OPEN||input.kind()==Kind.CLOSE)jdbc.update("UPDATE platform.cash_location SET session_open=?,custodian=?,session_date=? WHERE id=?",input.kind()==Kind.OPEN,job.maker(),input.businessDate(),input.locationId());
  if(input.kind()==Kind.CLOSE||input.kind()==Kind.COUNT)jdbc.update("UPDATE platform.cash_location SET counted_date=? WHERE id=?",input.businessDate(),job.entry().location().id());
  else if(monetary(input.kind())||input.kind()==Kind.OPEN)jdbc.update("UPDATE platform.cash_location SET counted_date=NULL WHERE id=? OR id=?",job.entry().location().id(),job.entry().destination()==null?job.entry().location().id():job.entry().destination().id());
  tenants.audit(new Actor(job.issuer(),job.checker(),false,job.correlationId()),bank.id(),"CASH_POSTED",bank.version(),bank.status(),bank.status(),"Completed approved request "+id,null,null,values(job.entry()));
 });}
}
