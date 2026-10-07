package com.cbs.accounts;

import static com.cbs.accounts.OperationModels.*;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankOperationStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final BankDatabaseProvisioner databases;
 private final BankJournalStore journals;
 public BankOperationStore(BankDatabaseProvisioner databases,BankJournalStore journals){this.databases=databases;this.journals=journals;}
 private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
 public boolean ready(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.customer_position') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
 public Position position(Tenant bank,AccountModels.Definition account){try(var c=connection(bank)){return position(c,account);}catch(SQLException e){throw unavailable();}}
 private Position position(Connection c,AccountModels.Definition account)throws SQLException{
  try(var s=c.prepareStatement("SELECT p.*,coalesce(l.credit,0)-coalesce(l.debit,0) gl FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=?")){
   s.setObject(1,account.id());try(var r=s.executeQuery()){
    if(!r.next())throw error("OPERATION_ACCOUNT_NOT_FUNDED");
    var posted=r.getBigDecimal("posted");var held=r.getBigDecimal("held");var available=posted.subtract(held);
    return new Position(text(posted),text(held),text(available),text(available.subtract(new BigDecimal(account.terms().minimumBalance())).max(BigDecimal.ZERO)),posted.compareTo(r.getBigDecimal("gl"))==0,r.getObject("ledger_id",UUID.class));
   }
  }
 }
 public boolean posted(Tenant bank,UUID id){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.account_operation WHERE request_id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
 public void validate(Tenant bank,Entry entry){try(var c=connection(bank)){validate(c,entry);}catch(SQLException e){throw unavailable();}}
 private void validate(Connection c,Entry e)throws SQLException{
  var input=e.change();var p=position(c,e.account());var amount=new BigDecimal(input.amount());
  if(!p.reconciled())throw error("FUNDING_UNRECONCILED");
  if(input.kind()!=Kind.RELEASE&&amount.compareTo(new BigDecimal(p.withdrawable()))>0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");
  if(input.kind()==Kind.TRANSFER&&!position(c,e.destination()).reconciled())throw error("FUNDING_UNRECONCILED");
  if(input.kind()==Kind.RELEASE){
   try(var s=c.prepareStatement("SELECT amount FROM bank.account_operation h WHERE request_id=? AND account_id=? AND kind='HOLD' AND NOT EXISTS(SELECT 1 FROM bank.account_operation r WHERE r.original_id=h.request_id)")){
    s.setObject(1,input.originalId());s.setObject(2,input.accountId());try(var r=s.executeQuery()){if(!r.next()||r.getBigDecimal(1).compareTo(amount)!=0)throw error("HOLD_NOT_ACTIVE");}
   }
  }
 }
 public void post(Tenant bank,Dispatch job){
  try(var c=connection(bank)){c.setAutoCommit(false);try{post(c,bank,job);c.commit();}catch(Exception e){c.rollback();throw e;}}
  catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
 }
 /** Participates in the caller's cash/customer/journal transaction without committing it. */
 public void post(Connection c,Tenant bank,Dispatch job)throws SQLException{
  var e=job.entry();var input=e.change();if(!bank.id().equals(e.bankId()))throw error("DATABASE_OWNERSHIP_MISMATCH");
   try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,3))")){s.setString(1,e.id().toString());s.execute();}
   var payload=JSON.writeValueAsString(e);
   try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.account_operation WHERE request_id=?")){s.setString(1,payload);s.setObject(2,e.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw new SQLException("Operation receipt mismatch");return;}}}
   var definitions=new ArrayList<AccountModels.Definition>();definitions.add(e.account());if(e.destination()!=null)definitions.add(e.destination());definitions.sort(Comparator.comparing(a->a.id().toString()));
   for(var a:definitions)try(var s=c.prepareStatement("SELECT payload FROM bank.account WHERE id=? AND tenant_id=? FOR UPDATE")){
    s.setObject(1,a.id());s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next()||!a.equals(JSON.readValue(r.getString(1),AccountModels.Definition.class)))throw error("ACCOUNT_RECONCILIATION_REQUIRED");}
   }
   validate(c,e);
   if(e.journal()!=null)journals.post(c,bank,new JournalModels.Dispatch(e.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   try(var s=c.prepareStatement("INSERT INTO bank.account_operation(request_id,kind,account_id,destination_id,amount,original_id,journal_id,payload) VALUES (?,?,?,?,?,?,?,?::jsonb)")){
    s.setObject(1,e.id());s.setString(2,input.kind().name());s.setObject(3,input.accountId());s.setObject(4,input.destinationId());s.setBigDecimal(5,new BigDecimal(input.amount()));s.setObject(6,input.originalId());s.setObject(7,e.journal()==null?null:e.id());s.setString(8,payload);s.executeUpdate();
   }
   if(e.journal()!=null){insertEntry(c,e.id(),input.accountId(),new BigDecimal(input.amount()).negate());if(e.destination()!=null)insertEntry(c,e.id(),input.destinationId(),new BigDecimal(input.amount()));}
 }
 private void insertEntry(Connection c,UUID id,UUID account,BigDecimal amount)throws SQLException{try(var s=c.prepareStatement("INSERT INTO bank.customer_entry(request_id,account_id,amount) VALUES (?,?,?)")){s.setObject(1,id);s.setObject(2,account);s.setBigDecimal(3,amount);s.executeUpdate();}}
 private static String text(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
 private static TenantException error(String code){return new TenantException(409,code,"Account operation requires attention");}
 private static TenantException unavailable(){return new TenantException(503,"OPERATION_DATABASE_UNAVAILABLE","Account operation could not be confirmed; retry safely");}
}
