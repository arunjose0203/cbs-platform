package com.cbs.accounts;

import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class StatementService {
 public record Line(UUID transactionId,LocalDate businessDate,String reference,String narration,String debit,String credit,String balance) {}
 public record Statement(String accountNumber,String currency,LocalDate from,LocalDate to,String openingBalance,String closingBalance,List<Line> items,long total,int limit,int offset) {}
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;private final BankDatabaseProvisioner databases;private final TransactionTemplate tx;
 public StatementService(JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,BankDatabaseProvisioner databases,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.databases=databases;this.tx=new TransactionTemplate(manager);}
 public Statement read(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to,int limit,int offset){
  AccessService.page(limit,offset);return readInternal(actor,bankId,accountId,from,to,limit,offset,false);
 }
 public Statement export(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to){
  return readInternal(actor,bankId,accountId,from,to,10000,0,true);
 }
 private Statement readInternal(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to,int limit,int offset,boolean export){
  AccessService.admin(actor);
  if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>366)throw new TenantException(400,"STATEMENT_DATE_RANGE","Select a date range of at most 367 days");
  return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var account=accounts.detail(actor,bankId,accountId).definition();
   try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
    c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
    boolean ready;try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.customer_entry') IS NOT NULL")){r.next();ready=r.getBoolean(1);}if(!ready)throw new TenantException(409,"OPERATION_STORAGE_NOT_READY","Prepare account storage first");
    boolean servicing;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.servicing_event') IS NOT NULL")){r.next();servicing=r.getBoolean(1);}
    boolean fx;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.fx_entry') IS NOT NULL")){r.next();fx=r.getBoolean(1);}
    boolean contracts;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.contract_entry') IS NOT NULL")){r.next();contracts=r.getBoolean(1);}
    String source="WITH selected AS (SELECT ?::uuid aid),entries AS (SELECT f.request_id,f.amount FROM bank.funding f WHERE f.account_id=(SELECT aid FROM selected) UNION ALL SELECT e.request_id,e.amount FROM bank.customer_entry e WHERE e.account_id=(SELECT aid FROM selected)"+(servicing?" UNION ALL SELECT s.request_id,s.net_amount AS amount FROM bank.servicing_event s WHERE s.account_id=(SELECT aid FROM selected)":"")+(fx?" UNION ALL SELECT x.journal_id,x.amount FROM bank.fx_entry x WHERE x.account_id=(SELECT aid FROM selected)":"")+(contracts?" UNION ALL SELECT x.event_id,x.amount FROM bank.contract_entry x WHERE x.account_id=(SELECT aid FROM selected)":"")+"), movements AS (SELECT j.id,j.business_date,j.reference,j.payload->>'narration' narration,j.posted_at,e.amount FROM entries e JOIN bank.journal j ON j.id=e.request_id) ";
    BigDecimal opening,closing;long count;
    try(var s=c.prepareStatement(source+"SELECT coalesce(sum(amount) FILTER(WHERE business_date<?),0),coalesce(sum(amount) FILTER(WHERE business_date<=?),0),count(*) FILTER(WHERE business_date BETWEEN ? AND ?) FROM movements")){
     s.setObject(1,accountId);s.setObject(2,from);s.setObject(3,to);s.setObject(4,from);s.setObject(5,to);try(var r=s.executeQuery()){r.next();opening=r.getBigDecimal(1);closing=r.getBigDecimal(2);count=r.getLong(3);}
    }
    if(export&&count>10000)throw new TenantException(400,"STATEMENT_EXPORT_TOO_LARGE","Select a shorter date range; exports support at most 10000 entries");
    List<Line> lines=new ArrayList<>();
    try(var s=c.prepareStatement(source+"SELECT *,?+sum(amount) OVER(ORDER BY business_date,posted_at,id ROWS UNBOUNDED PRECEDING) running FROM movements WHERE business_date BETWEEN ? AND ? ORDER BY business_date,posted_at,id LIMIT ? OFFSET ?")){
     s.setObject(1,accountId);s.setBigDecimal(2,opening);s.setObject(3,from);s.setObject(4,to);s.setInt(5,limit);s.setInt(6,offset);
     try(var r=s.executeQuery()){while(r.next()){var amount=r.getBigDecimal("amount");lines.add(new Line(r.getObject("id",UUID.class),r.getObject("business_date",LocalDate.class),r.getString("reference"),r.getString("narration"),text(amount.negate().max(BigDecimal.ZERO)),text(amount.max(BigDecimal.ZERO)),text(r.getBigDecimal("running"))));}}
    }
    c.commit();tenants.audit(actor,bankId,export?"ACCOUNT_STATEMENT_EXPORTED":"ACCOUNT_STATEMENT_VIEWED",bank.version(),bank.status(),bank.status(),export?"Customer statement exported":"Customer statement viewed",null,null,Map.of("accountId",accountId,"from",from.toString(),"to",to.toString()));
    return new Statement(account.accountNumber(),account.terms().currency(),from,to,text(opening),text(closing),lines,count,limit,offset);
   }catch(java.sql.SQLException e){throw new TenantException(503,"STATEMENT_DATABASE_UNAVAILABLE","Customer statement could not be read");}
  });
 }
 private static String text(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
