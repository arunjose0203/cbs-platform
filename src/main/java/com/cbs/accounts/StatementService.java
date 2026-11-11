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
 /** Statement header passed to a streaming sink before its lines. */
 public record Header(String bankName,String legalEntityName,String accountNumber,String accountName,String customerNumber,String currency,LocalDate from,LocalDate to,String openingBalance,String closingBalance,long total,String totalDebits,String totalCredits,String language,List<String> holders) {}
 /** Receives a statement without holding every line in memory. */
 public interface Sink { void header(Header header) throws java.io.IOException; void line(Line line) throws java.io.IOException; default void finish() throws java.io.IOException {} }
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final AccountService accounts;private final BankDatabaseProvisioner databases;private final TransactionTemplate tx;
 public StatementService(JdbcTemplate jdbc,TenantRepository tenants,AccountService accounts,BankDatabaseProvisioner databases,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.accounts=accounts;this.databases=databases;this.tx=new TransactionTemplate(manager);}
 public Statement read(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to,int limit,int offset){
  AccessService.page(limit,offset);return readInternal(actor,bankId,accountId,from,to,limit,offset,false);
 }
 public Statement export(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to){
  var lines=new ArrayList<Line>();var header=new Header[1];
  stream(actor,bankId,accountId,from,to,"CSV",new Sink(){public void header(Header h){header[0]=h;}public void line(Line l){lines.add(l);}});
  var h=header[0];return new Statement(h.accountNumber(),h.currency(),from,to,h.openingBalance(),h.closingBalance(),lines,h.total(),lines.size(),0);
 }
 /**
  * Streams every line in the range through a database cursor: no entry cap, constant memory.
  * The range limit (367 days), administrator access, a repeatable-read snapshot and the audit
  * record are the same as for the paged statement.
  */
 public void stream(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to,String format,Sink sink){
  AccessService.admin(actor);range(from,to);
  tx.executeWithoutResult(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var account=accounts.detail(actor,bankId,accountId).definition();
   String entity=account.legalEntityName()==null?"":account.legalEntityName();
   try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
    c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
    String source=source(c);BigDecimal opening,closing,debits,credits;long count;
    // Primary holder first, then joint holders and guardians, named from the bank's own records.
    var holderIds=jdbc.queryForList("SELECT customer_id FROM platform.account_registry WHERE id=? UNION ALL (SELECT customer_id FROM platform.account_party WHERE account_id=? AND role IN ('JOINT_HOLDER','GUARDIAN') ORDER BY role,customer_id)",UUID.class,accountId,accountId);
    var names=new HashMap<UUID,String>();
    try(var s=c.prepareStatement("SELECT id,payload->'profile'->>'fullName' FROM bank.customer WHERE id=ANY(?)")){s.setArray(1,c.createArrayOf("uuid",holderIds.toArray()));try(var r=s.executeQuery()){while(r.next())names.put(r.getObject(1,UUID.class),r.getString(2));}}
    var holders=holderIds.stream().map(id->names.getOrDefault(id,"")).filter(n->!n.isBlank()).toList();
    try(var s=c.prepareStatement(source+"SELECT coalesce(sum(amount) FILTER(WHERE business_date<?),0),coalesce(sum(amount) FILTER(WHERE business_date<=?),0),count(*) FILTER(WHERE business_date BETWEEN ? AND ?),coalesce(sum(-amount) FILTER(WHERE business_date BETWEEN ? AND ? AND amount<0),0),coalesce(sum(amount) FILTER(WHERE business_date BETWEEN ? AND ? AND amount>0),0) FROM movements")){
     s.setObject(1,accountId);s.setObject(2,from);s.setObject(3,to);s.setObject(4,from);s.setObject(5,to);s.setObject(6,from);s.setObject(7,to);s.setObject(8,from);s.setObject(9,to);
     try(var r=s.executeQuery()){r.next();opening=r.getBigDecimal(1);closing=r.getBigDecimal(2);count=r.getLong(3);debits=r.getBigDecimal(4);credits=r.getBigDecimal(5);}
    }
    sink.header(new Header(bank.displayName(),entity,account.accountNumber(),account.name(),account.customerNumber(),account.terms().currency(),from,to,text(opening),text(closing),count,text(debits),text(credits),bank.defaultLanguage(),holders));
    try(var s=c.prepareStatement(source+"SELECT *,?+sum(amount) OVER(ORDER BY business_date,posted_at,id ROWS UNBOUNDED PRECEDING) running FROM movements WHERE business_date BETWEEN ? AND ? ORDER BY business_date,posted_at,id")){
     s.setFetchSize(1000);s.setObject(1,accountId);s.setBigDecimal(2,opening);s.setObject(3,from);s.setObject(4,to);
     try(var r=s.executeQuery()){while(r.next())sink.line(line(r));}
    }
    sink.finish();c.commit();
    tenants.audit(actor,bankId,"ACCOUNT_STATEMENT_EXPORTED",bank.version(),bank.status(),bank.status(),"Customer statement exported",null,null,Map.of("accountId",accountId,"from",from.toString(),"to",to.toString(),"format",format,"entries",count));
   }catch(java.sql.SQLException e){throw new TenantException(503,"STATEMENT_DATABASE_UNAVAILABLE","Customer statement could not be read");}
   catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
  });
 }
 private static void range(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>366)throw new TenantException(400,"STATEMENT_DATE_RANGE","Select a date range of at most 367 days");}
 private static Line line(java.sql.ResultSet r)throws java.sql.SQLException{var amount=r.getBigDecimal("amount");return new Line(r.getObject("id",UUID.class),r.getObject("business_date",LocalDate.class),r.getString("reference"),r.getString("narration"),text(amount.negate().max(BigDecimal.ZERO)),text(amount.max(BigDecimal.ZERO)),text(r.getBigDecimal("running")));}
 private static String source(java.sql.Connection c)throws java.sql.SQLException{
  boolean ready;try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.customer_entry') IS NOT NULL")){r.next();ready=r.getBoolean(1);}if(!ready)throw new TenantException(409,"OPERATION_STORAGE_NOT_READY","Prepare account storage first");
  boolean servicing;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.servicing_event') IS NOT NULL")){r.next();servicing=r.getBoolean(1);}
  boolean fx;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.fx_entry') IS NOT NULL")){r.next();fx=r.getBoolean(1);}
  boolean contracts;try(var st=c.createStatement();var r=st.executeQuery("SELECT to_regclass('bank.contract_entry') IS NOT NULL")){r.next();contracts=r.getBoolean(1);}
  return "WITH selected AS (SELECT ?::uuid aid),entries AS (SELECT f.request_id,f.amount FROM bank.funding f WHERE f.account_id=(SELECT aid FROM selected) UNION ALL SELECT e.request_id,e.amount FROM bank.customer_entry e WHERE e.account_id=(SELECT aid FROM selected)"+(servicing?" UNION ALL SELECT s.request_id,s.net_amount AS amount FROM bank.servicing_event s WHERE s.account_id=(SELECT aid FROM selected)":"")+(fx?" UNION ALL SELECT x.journal_id,x.amount FROM bank.fx_entry x WHERE x.account_id=(SELECT aid FROM selected)":"")+(contracts?" UNION ALL SELECT x.event_id,x.amount FROM bank.contract_entry x WHERE x.account_id=(SELECT aid FROM selected)":"")+"), movements AS (SELECT j.id,j.business_date,j.reference,j.payload->>'narration' narration,j.posted_at,e.amount FROM entries e JOIN bank.journal j ON j.id=e.request_id) ";
 }
 private Statement readInternal(Actor actor,UUID bankId,UUID accountId,LocalDate from,LocalDate to,int limit,int offset,boolean export){
  AccessService.admin(actor);range(from,to);
  return tx.execute(t->{
   jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var account=accounts.detail(actor,bankId,accountId).definition();
   try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
    c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
    String source=source(c);BigDecimal opening,closing;long count;
    try(var s=c.prepareStatement(source+"SELECT coalesce(sum(amount) FILTER(WHERE business_date<?),0),coalesce(sum(amount) FILTER(WHERE business_date<=?),0),count(*) FILTER(WHERE business_date BETWEEN ? AND ?) FROM movements")){
     s.setObject(1,accountId);s.setObject(2,from);s.setObject(3,to);s.setObject(4,from);s.setObject(5,to);try(var r=s.executeQuery()){r.next();opening=r.getBigDecimal(1);closing=r.getBigDecimal(2);count=r.getLong(3);}
    }
    List<Line> lines=new ArrayList<>();
    try(var s=c.prepareStatement(source+"SELECT *,?+sum(amount) OVER(ORDER BY business_date,posted_at,id ROWS UNBOUNDED PRECEDING) running FROM movements WHERE business_date BETWEEN ? AND ? ORDER BY business_date,posted_at,id LIMIT ? OFFSET ?")){
     s.setObject(1,accountId);s.setBigDecimal(2,opening);s.setObject(3,from);s.setObject(4,to);s.setInt(5,limit);s.setInt(6,offset);
     try(var r=s.executeQuery()){while(r.next())lines.add(line(r));}
    }
    c.commit();tenants.audit(actor,bankId,"ACCOUNT_STATEMENT_VIEWED",bank.version(),bank.status(),bank.status(),"Customer statement viewed",null,null,Map.of("accountId",accountId,"from",from.toString(),"to",to.toString()));
    return new Statement(account.accountNumber(),account.terms().currency(),from,to,text(opening),text(closing),lines,count,limit,offset);
   }catch(java.sql.SQLException e){throw new TenantException(503,"STATEMENT_DATABASE_UNAVAILABLE","Customer statement could not be read");}
  });
 }
 private static String text(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
