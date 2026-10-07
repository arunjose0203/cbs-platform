package com.cbs.servicing;
import static com.cbs.servicing.ServicingModels.*;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankServicingStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final BankDatabaseProvisioner databases;private final BankJournalStore journals;
 public BankServicingStore(BankDatabaseProvisioner databases,BankJournalStore journals){this.databases=databases;this.journals=journals;}
 private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
 public boolean ready(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.servicing_event') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
 public boolean posted(Tenant bank,UUID id){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.servicing_run WHERE request_id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
 public BigDecimal carry(Tenant bank,UUID account){try(var c=connection(bank);var s=c.prepareStatement("SELECT carry_after FROM bank.servicing_event WHERE account_id=? ORDER BY business_date DESC LIMIT 1")){s.setObject(1,account);try(var r=s.executeQuery()){return r.next()?r.getBigDecimal(1):BigDecimal.ZERO;}}catch(SQLException e){throw unavailable();}}
 public void post(Tenant bank,Dispatch job){
  var batch=job.batch();if(!bank.id().equals(batch.bankId()))throw unavailable();
  try(var c=connection(bank)){c.setAutoCommit(false);try{
   try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,17))")){s.setString(1,bank.id().toString());s.execute();}
   var payload=JSON.writeValueAsString(batch);
   try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.servicing_run WHERE request_id=?")){s.setString(1,payload);s.setObject(2,batch.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}}
   for(var item:batch.items().stream().sorted(Comparator.comparing(i->i.account().id())).toList()){
    try(var s=c.prepareStatement("SELECT payload FROM bank.account WHERE id=? AND tenant_id=? FOR UPDATE")){s.setObject(1,item.account().id());s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next()||!item.account().equals(JSON.readValue(r.getString(1),com.cbs.accounts.AccountModels.Definition.class)))throw new TenantException(409,"ACCOUNT_RECONCILIATION_REQUIRED","Servicing account changed");}}
    try(var s=c.prepareStatement("SELECT p.posted,p.held,coalesce(l.credit-l.debit,0) book FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=?")){s.setObject(1,item.account().id());try(var r=s.executeQuery()){if(!r.next()||r.getBigDecimal(1).compareTo(new BigDecimal(item.openingBalance()))!=0||r.getBigDecimal(2).compareTo(new BigDecimal(item.held()))!=0||r.getBigDecimal(1).compareTo(r.getBigDecimal(3))!=0)throw new TenantException(409,"SERVICING_BALANCE_CHANGED","Servicing snapshot does not match current balances");}}
   }
   try(var s=c.prepareStatement("INSERT INTO bank.servicing_run VALUES (?,?,?,?::jsonb)")){s.setObject(1,batch.id());s.setObject(2,batch.businessDate());s.setObject(3,batch.nextBusinessDate());s.setString(4,payload);s.executeUpdate();}
   for(var item:batch.items()){
    if(item.journal()!=null)journals.post(c,bank,new JournalModels.Dispatch(item.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
    try(var s=c.prepareStatement("INSERT INTO bank.servicing_event(request_id,run_id,account_id,business_date,opening_balance,net_amount,carry_after,journal_id,payload) VALUES (?,?,?,?,?,?,?,?,?::jsonb)")){
     s.setObject(1,item.id());s.setObject(2,batch.id());s.setObject(3,item.account().id());s.setObject(4,batch.businessDate());s.setBigDecimal(5,new BigDecimal(item.openingBalance()));s.setBigDecimal(6,new BigDecimal(item.calculation().netChange()));s.setBigDecimal(7,new BigDecimal(item.calculation().carryAfter()));s.setObject(8,item.journal()==null?null:item.id());s.setString(9,JSON.writeValueAsString(item));s.executeUpdate();
    }
   }
   c.commit();
  }catch(Exception e){c.rollback();throw e;}}
  catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
 }
 private static TenantException unavailable(){return new TenantException(503,"SERVICING_DATABASE_UNAVAILABLE","Daily processing could not be confirmed; retry safely");}
}
