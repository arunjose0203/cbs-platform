package com.cbs.fx;
import static com.cbs.fx.FxTradeModels.*;
import com.cbs.accounts.*;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
@Component
public class BankFxStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();private final BankDatabaseProvisioner databases;private final BankJournalStore journals;
 public BankFxStore(BankDatabaseProvisioner databases,BankJournalStore journals){this.databases=databases;this.journals=journals;}
 public boolean ready(Tenant bank){try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.fx_trade') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
 public boolean posted(Tenant bank,UUID id){try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.fx_trade WHERE id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
 public void post(Tenant bank,Dispatch job){var b=job.batch();if(!bank.id().equals(b.bankId()))throw unavailable();try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){c.setAutoCommit(false);try{
  try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,28))")){s.setString(1,b.id().toString());s.execute();}
  String payload=JSON.writeValueAsString(b);try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.fx_trade WHERE id=?")){s.setString(1,payload);s.setObject(2,b.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}}
  for(var a:List.of(b.source(),b.target()).stream().sorted(Comparator.comparing(AccountModels.Definition::id)).toList()){
   try(var s=c.prepareStatement("SELECT payload FROM bank.account WHERE tenant_id=? AND id=? FOR UPDATE")){s.setObject(1,bank.id());s.setObject(2,a.id());try(var r=s.executeQuery()){if(!r.next()||!a.equals(JSON.readValue(r.getString(1),AccountModels.Definition.class)))throw error("ACCOUNT_RECONCILIATION_REQUIRED");}}
   try(var s=c.prepareStatement("SELECT p.posted,p.held,coalesce(l.credit-l.debit,0) gl FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=?")){s.setObject(1,a.id());try(var r=s.executeQuery()){if(!r.next()||r.getBigDecimal(1).compareTo(r.getBigDecimal(3))!=0)throw error("FUNDING_UNRECONCILED");if(a.id().equals(b.source().id())&&r.getBigDecimal(1).subtract(r.getBigDecimal(2)).subtract(new BigDecimal(a.terms().minimumBalance())).compareTo(new BigDecimal(b.quote().sourceAmount()))<0)throw error("INSUFFICIENT_AVAILABLE_BALANCE");}}
  }
  journals.post(c,bank,new JournalModels.Dispatch(b.sourceJournal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
  journals.post(c,bank,new JournalModels.Dispatch(b.targetJournal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
  try(var s=c.prepareStatement("INSERT INTO bank.fx_trade(id,tenant_id,original_id,source_journal,target_journal,payload) VALUES (?,?,?,?,?,?::jsonb)")){s.setObject(1,b.id());s.setObject(2,bank.id());s.setObject(3,b.originalId());s.setObject(4,b.sourceJournal().id());s.setObject(5,b.targetJournal().id());s.setString(6,payload);s.executeUpdate();}
  entry(c,b,b.source().id(),b.sourceJournal().id(),new BigDecimal(b.quote().sourceAmount()).negate());entry(c,b,b.target().id(),b.targetJournal().id(),new BigDecimal(b.quote().targetAmount()));c.commit();
 }catch(Exception e){c.rollback();throw e;}}catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}}
 private void entry(Connection c,Batch b,UUID account,UUID journal,BigDecimal amount)throws SQLException{try(var s=c.prepareStatement("INSERT INTO bank.fx_entry(trade_id,account_id,journal_id,amount) VALUES (?,?,?,?)")){s.setObject(1,b.id());s.setObject(2,account);s.setObject(3,journal);s.setBigDecimal(4,amount);s.executeUpdate();}}
 private static TenantException error(String code){return new TenantException(409,code,"FX posting requires attention");}
 private static TenantException unavailable(){return new TenantException(503,"FX_DATABASE_UNAVAILABLE","FX posting could not be confirmed; retry safely");}
}
