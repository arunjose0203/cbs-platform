package com.cbs.accounts;
import static com.cbs.accounts.FundingModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import com.cbs.accounting.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
@Component
public class BankFundingStore {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final BankDatabaseProvisioner databases;private final BankJournalStore journals;private final BankAccountStore accounts;
 public BankFundingStore(BankDatabaseProvisioner databases,BankJournalStore journals,BankAccountStore accounts){this.databases=databases;this.journals=journals;this.accounts=accounts;}
 private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
 public boolean ready(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.funding') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
 public Balance balance(Tenant bank,AccountModels.Definition account){
  try(var c=connection(bank)){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);var result=balance(c,account);c.commit();return result;}catch(SQLException e){throw unavailable();}
 }
 private Balance balance(Connection c,AccountModels.Definition account)throws SQLException{
  boolean operations;try(var st=c.createStatement();var rs=st.executeQuery("SELECT to_regclass('bank.customer_position') IS NOT NULL")){rs.next();operations=rs.getBoolean(1);}
  if(operations)try(var st=c.prepareStatement("SELECT p.posted,coalesce(l.credit,0)-coalesce(l.debit,0) gl,p.ledger_id FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=?")){st.setObject(1,account.id());try(var rs=st.executeQuery()){if(!rs.next())return new Balance(account.id(),account.terms().currency(),"0","0",true,null);var posted=rs.getBigDecimal(1);var gl=rs.getBigDecimal(2);return new Balance(account.id(),account.terms().currency(),posted.stripTrailingZeros().toPlainString(),gl.stripTrailingZeros().toPlainString(),posted.compareTo(gl)==0,rs.getObject(3,UUID.class));}}
  try(var s=c.prepareStatement("SELECT b.ledger_id,coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=b.account_id),0) posted,coalesce(l.credit,0)-coalesce(l.debit,0) gl FROM bank.account_ledger b LEFT JOIN bank.ledger_balance l ON l.ledger_id=b.ledger_id WHERE b.account_id=?")){
   s.setObject(1,account.id());try(var r=s.executeQuery()){if(!r.next())return new Balance(account.id(),account.terms().currency(),"0","0",true,null);
    var posted=r.getBigDecimal("posted");var gl=r.getBigDecimal("gl");return new Balance(account.id(),account.terms().currency(),posted.stripTrailingZeros().toPlainString(),gl.stripTrailingZeros().toPlainString(),posted.compareTo(gl)==0,r.getObject("ledger_id",UUID.class));}
  }
 }
 public boolean unused(Tenant bank,UUID ledger){
  try(var c=connection(bank);var s=c.prepareStatement("SELECT NOT EXISTS(SELECT 1 FROM bank.journal_line WHERE ledger_id=?) AND NOT EXISTS(SELECT 1 FROM bank.account_ledger WHERE ledger_id=?)")){
   s.setObject(1,ledger);s.setObject(2,ledger);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}
  }catch(SQLException e){throw unavailable();}
 }
 public boolean posted(Tenant bank,UUID id){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.funding WHERE request_id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
 public void post(Tenant bank,Dispatch job){
  try(var c=connection(bank)){c.setAutoCommit(false);try{post(c,bank,job);c.commit();}catch(Exception e){c.rollback();throw e;}}
  catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
 }
 /** Participates in the caller's cash/customer/journal transaction without committing it. */
 public void post(Connection c,Tenant bank,Dispatch job)throws SQLException{
  var f=job.entry();if(!f.bankId().equals(bank.id())||!f.accountId().equals(f.account().id()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong funding route");
   try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,2))")){s.setString(1,f.id().toString());s.execute();}
   String payload=JSON.writeValueAsString(f);
   try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.funding WHERE request_id=?")){s.setString(1,payload);s.setObject(2,f.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw new SQLException("Funding receipt mismatch");return;}}}
   AccountModels.Definition before;
   try(var s=c.prepareStatement("SELECT payload FROM bank.account WHERE id=? AND tenant_id=? FOR UPDATE")){s.setObject(1,f.accountId());s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing account");before=JSON.readValue(r.getString(1),AccountModels.Definition.class);}}
   if(before.version()+1!=f.account().version()||!List.of(AccountModels.State.AWAITING_FUNDING,AccountModels.State.ACTIVE).contains(before.state()))throw new TenantException(409,"FUNDING_ACCOUNT_NOT_READY","Account cannot receive funding");
   var current=balance(c,before);if(!current.reconciled())throw new TenantException(409,"FUNDING_UNRECONCILED","Customer balance differs from ledger");
   if(current.liabilityLedgerId()==null){
    if(new BigDecimal(f.amount()).compareTo(new BigDecimal(before.terms().minimumOpening()))<0)throw new TenantException(400,"FUNDING_OPENING_MINIMUM","Opening deposit below minimum");
    try(var s=c.prepareStatement("SELECT id FROM bank.ledger_account WHERE id=? AND tenant_id=? AND legal_entity_id=? AND currency=? AND chart_account_id=? AND active FOR UPDATE")){
     s.setObject(1,f.liabilityLedgerId());s.setObject(2,bank.id());s.setObject(3,before.legalEntityId());s.setString(4,before.terms().currency());s.setObject(5,before.terms().liabilityAccountId());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Liability scope mismatch");}}
    try(var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.journal_line WHERE ledger_id=?)")){s.setObject(1,f.liabilityLedgerId());try(var r=s.executeQuery()){r.next();if(r.getBoolean(1))throw new TenantException(409,"FUNDING_LEDGER_USED","Use an unused dedicated ledger");}}
    try(var s=c.prepareStatement("INSERT INTO bank.account_ledger(account_id,ledger_id) VALUES (?,?)")){s.setObject(1,f.accountId());s.setObject(2,f.liabilityLedgerId());s.executeUpdate();}
   }else if(!current.liabilityLedgerId().equals(f.liabilityLedgerId()))throw new SQLException("Funding binding mismatch");
   journals.post(c,bank,new JournalModels.Dispatch(f.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   accounts.deliver(c,bank,new AccountModels.Dispatch(f.id(),f.account(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   try(var s=c.prepareStatement("INSERT INTO bank.funding(request_id,account_id,amount,payload) VALUES (?,?,?,?::jsonb)")){s.setObject(1,f.id());s.setObject(2,f.accountId());s.setBigDecimal(3,new BigDecimal(f.amount()));s.setString(4,payload);s.executeUpdate();}
 }
 private static TenantException unavailable(){return new TenantException(503,"FUNDING_DATABASE_UNAVAILABLE","Funding could not be confirmed; retry safely");}
}
