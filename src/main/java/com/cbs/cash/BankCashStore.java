package com.cbs.cash;

import static com.cbs.cash.CashModels.*;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankCashStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final com.cbs.accounts.BankFundingStore funding;private final com.cbs.accounts.BankOperationStore operations;
 private final BankDatabaseProvisioner databases;private final BankJournalStore journals;
 public BankCashStore(BankDatabaseProvisioner databases,BankJournalStore journals,com.cbs.accounts.BankFundingStore funding,com.cbs.accounts.BankOperationStore operations){this.funding=funding;this.operations=operations;this.databases=databases;this.journals=journals;}
 private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
 public boolean ready(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.cash_transit') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw error("CASH_DATABASE_UNAVAILABLE");}}
 public boolean posted(Tenant bank,UUID id){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.cash_receipt WHERE request_id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw error("CASH_DATABASE_UNAVAILABLE");}}
 public Position position(Tenant bank,Location location){try(var c=connection(bank)){return position(c,location);}catch(SQLException e){throw error("CASH_DATABASE_UNAVAILABLE");}}
 private Position position(Connection c,Location location)throws SQLException{
  try(var s=c.prepareStatement("SELECT l.payload::text,l.session_open,l.custodian,l.session_date,p.balance,coalesce(b.debit-b.credit,0) book,l.counted_date FROM bank.cash_location l JOIN bank.cash_position p ON p.id=l.id LEFT JOIN bank.ledger_balance b ON b.ledger_id=l.ledger_id WHERE l.id=? AND l.tenant_id=?")){
   s.setObject(1,location.id());s.setObject(2,location.bankId());try(var r=s.executeQuery()){
    if(!r.next()||!JSON.readValue(r.getString(1),Location.class).equals(location))throw error("CASH_LOCATION_NOT_READY");
    var balance=r.getBigDecimal("balance");var book=r.getBigDecimal("book");
    return new Position(location,balance.toPlainString(),book.toPlainString(),balance.compareTo(book)==0,r.getBoolean(2),r.getString(3),r.getObject(4,java.time.LocalDate.class),r.getObject("counted_date",java.time.LocalDate.class));
   }
  }
 }
 public void unused(Tenant bank,UUID ledger){try(var c=connection(bank)){unused(c,ledger);}catch(SQLException e){throw error("CASH_DATABASE_UNAVAILABLE");}}
 private void unused(Connection c,UUID ledger)throws SQLException{
  try(var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.journal_line WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM bank.account_ledger WHERE ledger_id=?) OR EXISTS(SELECT 1 FROM bank.cash_location WHERE ledger_id=?)")){
   s.setObject(1,ledger);s.setObject(2,ledger);s.setObject(3,ledger);try(var r=s.executeQuery()){r.next();if(r.getBoolean(1))throw error("CASH_LEDGER_USED");}
  }
 }
 public void validate(Tenant bank,Entry entry,String maker){
  if(entry.change().kind()==Kind.REGISTER){unused(bank,entry.location().ledgerId());return;}
  validatePosition(position(bank,entry.location()),entry,maker,false);
  if(entry.destination()!=null)validatePosition(position(bank,entry.destination()),entry,maker,true);
 }
 private void validatePosition(Position p,Entry entry,String maker,boolean destination){
  var change=entry.change();
  if(!p.reconciled())throw error("CASH_UNRECONCILED");
  if(adjustment(change.kind())){
   var delta=new BigDecimal(change.amount()).multiply(change.kind()==Kind.OVERAGE?BigDecimal.ONE:BigDecimal.ONE.negate());
   if(change.exceptionId()==null||new BigDecimal(p.balance()).add(delta).compareTo(counted(p.location().currency(),change.counts()))!=0)throw error("CASH_ADJUSTMENT_MISMATCH");
   return;
  }
  if(change.kind()==Kind.OPEN){if(p.sessionOpen())throw error("CASH_SESSION_OPEN");}
  else if(change.kind()==Kind.COUNT){if(p.sessionOpen())throw error("CASH_SESSION_OPEN");}
  else if(p.location().type()==Type.TILL){
   if(!p.sessionOpen()||!change.businessDate().equals(p.sessionDate()))throw error("CASH_SESSION_REQUIRED");
   if(!maker.equals(p.custodian()))throw error("CASH_CUSTODIAN_REQUIRED");
  }
  if(change.kind()==Kind.REVERSAL){
   if(new BigDecimal(p.balance()).add(delta(entry,p.location().ledgerId())).signum()<0)throw error("CASH_INSUFFICIENT");return;
  }
  if(change.kind()==Kind.OPEN||change.kind()==Kind.CLOSE||change.kind()==Kind.COUNT){
   if(counted(p.location().currency(),change.counts()).compareTo(new BigDecimal(p.balance()))!=0)throw error("CASH_COUNT_MISMATCH");
  }else if(!destination&&change.kind()!=Kind.RECEIVE&&change.kind()!=Kind.DEPOSIT&&new BigDecimal(p.balance()).compareTo(new BigDecimal(change.amount()))<0)throw error("CASH_INSUFFICIENT");
 }
 public void post(Tenant bank,Dispatch job){
  var e=job.entry();if(!bank.id().equals(e.bankId()))throw error("DATABASE_OWNERSHIP_MISMATCH");
  try(var c=connection(bank)){c.setAutoCommit(false);try{
   try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,7))")){s.setString(1,bank.id().toString());s.execute();}
   String payload=JSON.writeValueAsString(e);
   try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.cash_receipt WHERE request_id=?")){s.setString(1,payload);s.setObject(2,e.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw error("CASH_RECEIPT_MISMATCH");c.commit();return;}}}
   var input=e.change();var l=e.location();
   if(e.funding()!=null||e.operation()!=null){UUID account=e.funding()!=null?e.funding().accountId():e.operation().account().id();try(var s=c.prepareStatement("SELECT id FROM bank.account WHERE id=? AND tenant_id=? FOR UPDATE")){s.setObject(1,account);s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next())throw error("ACCOUNT_NOT_FOUND");}}}
   // Match the journal writer's lock order before checking cash availability.
   var ledgers=new ArrayList<UUID>();ledgers.add(l.ledgerId());if(e.destination()!=null)ledgers.add(e.destination().ledgerId());if(e.journal()!=null)for(var line:e.journal().lines())ledgers.add(line.ledgerId());
   for(var id:ledgers.stream().distinct().sorted().toList())try(var s=c.prepareStatement("SELECT id FROM bank.ledger_account WHERE id=? AND tenant_id=? AND active FOR UPDATE")){s.setObject(1,id);s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next())throw error("JOURNAL_LEDGER_NOT_READY");}}
   if(input.kind()==Kind.REGISTER){
    unused(c,l.ledgerId());try(var s=c.prepareStatement("INSERT INTO bank.cash_location(id,tenant_id,branch_id,ledger_id,code,name,currency,kind,legal_entity_id,payload) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)")){
     s.setObject(1,l.id());s.setObject(2,bank.id());s.setObject(3,l.branchId());s.setObject(4,l.ledgerId());s.setString(5,l.code());s.setString(6,l.name());s.setString(7,l.currency());s.setString(8,l.type().name());s.setObject(9,l.legalEntityId());s.setString(10,JSON.writeValueAsString(l));s.executeUpdate();
    }
   }else{
    validatePosition(position(c,l),e,job.maker(),false);
    if(e.destination()!=null)validatePosition(position(c,e.destination()),e,job.maker(),true);
   }
   if(e.funding()!=null)funding.post(c,bank,new com.cbs.accounts.FundingModels.Dispatch(e.funding(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   else if(e.operation()!=null)operations.post(c,bank,new com.cbs.accounts.OperationModels.Dispatch(e.operation(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   else if(e.journal()!=null)journals.post(c,bank,new JournalModels.Dispatch(e.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   try(var s=c.prepareStatement("INSERT INTO bank.cash_receipt(request_id,kind,location_id,destination_id,journal_id,amount,payload,maker_subject,reviewer_subject,exception_id,original_id) VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?)")){
    s.setObject(1,e.id());s.setString(2,input.kind().name());s.setObject(3,l.id());s.setObject(4,e.destination()==null?null:e.destination().id());s.setObject(5,e.journal()==null?null:e.id());s.setBigDecimal(6,input.amount()==null?null:new BigDecimal(input.amount()));s.setString(7,payload);s.setString(8,job.maker());s.setString(9,job.checker());s.setObject(10,input.exceptionId());s.setObject(11,input.originalId());s.executeUpdate();
   }
   if(monetary(input.kind())){
    entry(c,e.id(),l.id(),delta(e,l.ledgerId()));
    if(e.destination()!=null)entry(c,e.id(),e.destination().id(),delta(e,e.destination().ledgerId()));
   }else if(input.kind()==Kind.OPEN||input.kind()==Kind.CLOSE){
    try(var s=c.prepareStatement("UPDATE bank.cash_location SET session_open=?,custodian=?,session_date=? WHERE id=?")){s.setBoolean(1,input.kind()==Kind.OPEN);s.setString(2,job.maker());s.setObject(3,input.businessDate());s.setObject(4,l.id());s.executeUpdate();}
   }
   if(input.kind()==Kind.CLOSE||input.kind()==Kind.COUNT){
    try(var s=c.prepareStatement("UPDATE bank.cash_location SET counted_date=? WHERE id=?")){s.setObject(1,input.businessDate());s.setObject(2,l.id());s.executeUpdate();}
   }else if(monetary(input.kind())||input.kind()==Kind.OPEN){
    try(var s=c.prepareStatement("UPDATE bank.cash_location SET counted_date=NULL WHERE id=? OR id=?")){s.setObject(1,l.id());s.setObject(2,e.destination()==null?l.id():e.destination().id());s.executeUpdate();}
   }
   c.commit();
  }catch(Exception exception){c.rollback();throw exception;}}
  catch(TenantException e1){throw e1;}catch(Exception exception){throw error("CASH_DATABASE_UNAVAILABLE");}
 }
 private BigDecimal delta(Entry e,UUID ledger){return e.journal().lines().stream().filter(l->l.ledgerId().equals(ledger)).map(l->new BigDecimal(l.amount()).multiply(l.side()==JournalModels.Side.DEBIT?BigDecimal.ONE:BigDecimal.ONE.negate())).reduce(BigDecimal.ZERO,BigDecimal::add);}
 private void entry(Connection c,UUID request,UUID location,BigDecimal amount)throws SQLException{try(var s=c.prepareStatement("INSERT INTO bank.cash_entry(request_id,location_id,amount) VALUES (?,?,?)")){s.setObject(1,request);s.setObject(2,location);s.setBigDecimal(3,amount);s.executeUpdate();}}
}
