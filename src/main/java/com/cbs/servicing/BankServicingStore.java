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
 /** One account's bank-side state, read in bulk for batch preparation. */
 public record Snapshot(com.cbs.accounts.AccountModels.Definition account,BigDecimal posted,BigDecimal held,BigDecimal book,UUID ledgerId,BigDecimal carry) {
  public boolean reconciled(){return posted.compareTo(book)==0;}
 }
 private static final String SNAPSHOT="SELECT a.id,a.payload,p.posted,p.held,coalesce(l.credit,0)-coalesce(l.debit,0) book,p.ledger_id,"+
  "coalesce((SELECT e.carry_after FROM bank.servicing_event e WHERE e.account_id=a.id ORDER BY e.business_date DESC LIMIT 1),0) carry "+
  "FROM bank.account a JOIN bank.customer_position p ON p.account_id=a.id LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE a.tenant_id=? AND a.id=ANY(?)";
 public Map<UUID,Snapshot> snapshot(Tenant bank,Collection<UUID> ids){
  try(var c=connection(bank)){return snapshot(c,bank,ids,false);}catch(SQLException e){throw unavailable();}
 }
 private Map<UUID,Snapshot> snapshot(Connection c,Tenant bank,Collection<UUID> ids,boolean lock)throws SQLException{
  var result=new HashMap<UUID,Snapshot>();if(ids.isEmpty())return result;var sorted=ids.stream().sorted().toArray();
  if(lock)try(var s=c.prepareStatement("SELECT id FROM bank.account WHERE tenant_id=? AND id=ANY(?) ORDER BY id FOR UPDATE")){s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",sorted));s.executeQuery().close();}
  try(var s=c.prepareStatement(SNAPSHOT)){s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",sorted));try(var r=s.executeQuery()){while(r.next())result.put(r.getObject(1,UUID.class),new Snapshot(JSON.readValue(r.getString(2),com.cbs.accounts.AccountModels.Definition.class),r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getObject(6,UUID.class),r.getBigDecimal(7)));}}
  return result;
 }
 public boolean chunked(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.servicing_chunk') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
 public boolean chunkPosted(Tenant bank,UUID run,int chunk){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.servicing_chunk WHERE run_id=? AND chunk_no=?)")){s.setObject(1,run);s.setInt(2,chunk);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
 /** Inserts the run header once; an existing header must match exactly. */
 private void header(Connection c,Tenant bank,Batch batch)throws SQLException{
  try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,17))")){s.setString(1,bank.id().toString());s.execute();}
  var payload=JSON.writeValueAsString(batch);
  try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.servicing_run WHERE request_id=?")){s.setString(1,payload);s.setObject(2,batch.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();return;}}}
  try(var s=c.prepareStatement("INSERT INTO bank.servicing_run VALUES (?,?,?,?::jsonb)")){s.setObject(1,batch.id());s.setObject(2,batch.businessDate());s.setObject(3,batch.nextBusinessDate());s.setString(4,payload);s.executeUpdate();}
 }
 /** Seals the bank day for a run with no accounts to post. */
 public void postHeader(Tenant bank,Dispatch job){
  if(!bank.id().equals(job.batch().bankId()))throw unavailable();
  try(var c=connection(bank)){c.setAutoCommit(false);try{header(c,bank,job.batch());c.commit();}catch(Exception e){c.rollback();throw e;}}
  catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
 }
 /**
  * Posts one chunk atomically: header (first time), chunk receipt, planned items, journals and events.
  * A repeated call with the same chunk is acknowledged from the receipt without posting again.
  */
 public void postChunk(Tenant bank,Dispatch job,Chunk chunk){
  var batch=job.batch();if(!bank.id().equals(batch.bankId())||!chunk.runId().equals(batch.id()))throw unavailable();
  try(var c=connection(bank)){c.setAutoCommit(false);try{
   header(c,bank,batch);var payload=JSON.writeValueAsString(chunk);
   try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.servicing_chunk WHERE run_id=? AND chunk_no=?")){s.setString(1,payload);s.setObject(2,batch.id());s.setInt(3,chunk.number());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}}
   var current=snapshot(c,bank,chunk.items().stream().map(i->i.account().id()).toList(),true);
   for(var item:chunk.items()){
    var now=current.get(item.account().id());
    if(now==null||!item.account().equals(now.account()))throw new TenantException(409,"ACCOUNT_RECONCILIATION_REQUIRED","Servicing account changed");
    if(now.posted().compareTo(new BigDecimal(item.openingBalance()))!=0||now.held().compareTo(new BigDecimal(item.held()))!=0||!now.reconciled()||now.carry().compareTo(new BigDecimal(item.carryBefore()))!=0)throw new TenantException(409,"SERVICING_BALANCE_CHANGED","Servicing snapshot does not match current balances");
   }
   try(var s=c.prepareStatement("INSERT INTO bank.servicing_chunk(run_id,chunk_no,payload) VALUES (?,?,?::jsonb)")){s.setObject(1,batch.id());s.setInt(2,chunk.number());s.setString(3,payload);s.executeUpdate();}
   try(var s=c.prepareStatement("INSERT INTO bank.servicing_item(item_id,run_id,chunk_no,account_id) VALUES (?,?,?,?)")){
    for(var item:chunk.items()){s.setObject(1,item.id());s.setObject(2,batch.id());s.setInt(3,chunk.number());s.setObject(4,item.account().id());s.addBatch();}s.executeBatch();
   }
   for(var item:chunk.items())if(item.journal()!=null)journals.post(c,bank,new JournalModels.Dispatch(item.journal(),job.maker(),job.issuer(),job.checker(),job.correlationId()));
   try(var s=c.prepareStatement("INSERT INTO bank.servicing_event(request_id,run_id,account_id,business_date,opening_balance,net_amount,carry_after,journal_id,payload) VALUES (?,?,?,?,?,?,?,?,?::jsonb)")){
    for(var item:chunk.items()){s.setObject(1,item.id());s.setObject(2,batch.id());s.setObject(3,item.account().id());s.setObject(4,batch.businessDate());s.setBigDecimal(5,new BigDecimal(item.openingBalance()));s.setBigDecimal(6,new BigDecimal(item.calculation().netChange()));s.setBigDecimal(7,new BigDecimal(item.calculation().carryAfter()));s.setObject(8,item.journal()==null?null:item.id());s.setString(9,JSON.writeValueAsString(item));s.addBatch();}
    s.executeBatch();
   }
   c.commit();
  }catch(Exception e){c.rollback();throw e;}}
  catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
 }
 private static TenantException unavailable(){return new TenantException(503,"SERVICING_DATABASE_UNAVAILABLE","Daily processing could not be confirmed; retry safely");}
}
