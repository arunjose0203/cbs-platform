package com.cbs.accounting;

import static com.cbs.accounting.JournalModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankJournalStore {
    private org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties){this.duties=duties;}
    /** Duty recheck for a request the bank has no receipt for yet (see BankDuties.requireAtDelivery). */
    private void recheck(java.util.UUID request){var service=duties==null?null:duties.getIfAvailable();if(service!=null)service.requireAtDelivery(request);}

    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final BankDatabaseProvisioner databases;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> control;
    public BankJournalStore(BankDatabaseProvisioner databases,org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> control){this.databases=databases;this.control=control;}
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    public boolean ready(Tenant bank){
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.journal') IS NOT NULL AND to_regclass('bank.ledger_balance') IS NOT NULL")){
            r.next();return r.getBoolean(1);
        }catch(SQLException e){throw unavailable();}
    }
    public Entry read(Tenant bank,UUID id){
        try(var c=connection(bank);var s=c.prepareStatement("SELECT payload::text FROM bank.journal WHERE id=? AND tenant_id=?")){
            s.setObject(1,id);s.setObject(2,bank.id());try(var r=s.executeQuery()){
                if(!r.next())throw new TenantException(404,"JOURNAL_NOT_POSTED","Journal has not posted");
                return JSON.readValue(r.getString(1),Entry.class);
            }
        }catch(SQLException e){throw unavailable();}
    }
    public List<Balance> balances(Tenant bank,int limit,int offset){
        try(var c=connection(bank);var s=c.prepareStatement("SELECT a.*,coalesce(b.debit,0) debit,coalesce(b.credit,0) credit FROM bank.ledger_account a LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id WHERE a.tenant_id=? ORDER BY a.code,a.id LIMIT ? OFFSET ?")){
            s.setObject(1,bank.id());s.setInt(2,limit);s.setInt(3,offset);
            try(var r=s.executeQuery()){List<Balance> rows=new ArrayList<>();while(r.next()){
                var debit=r.getBigDecimal("debit");var credit=r.getBigDecimal("credit");
                rows.add(new Balance(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getObject("legal_entity_id",UUID.class),r.getString("currency"),money(debit),money(credit),money(debit.subtract(credit))));
            }return rows;}
        }catch(SQLException e){throw unavailable();}
    }
    public boolean hasBalance(Tenant bank,UUID ledgerId){
        if(!ready(bank))return false;
        try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.ledger_balance WHERE ledger_id=? AND debit<>credit)")){
            s.setObject(1,ledgerId);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}
        }catch(SQLException e){throw unavailable();}
    }
    public void post(Tenant bank,Dispatch job){
        try(var c=connection(bank)){c.setAutoCommit(false);try{post(c,bank,job);c.commit();}catch(Exception e){c.rollback();throw e;}}
        catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    /** Writes into the caller transaction; never commits independently. */
    public void post(Connection c,Tenant bank,Dispatch job)throws SQLException{
        Entry entry=job.entry();
        if(!entry.bankId().equals(bank.id()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong bank route");
                try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,1))")){s.setString(1,entry.id().toString());s.execute();}
                String payload=JSON.writeValueAsString(entry);
                try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.journal WHERE id=?")){
                    s.setString(1,payload);s.setObject(2,entry.id());try(var r=s.executeQuery()){if(r.next()){
                        if(!r.getBoolean(1))throw new SQLException("Journal receipt mismatch");return;
                    }}
                }
                // Existing receipts above remain recoverable after access is withdrawn.
        com.cbs.access.AccessService.requireActiveParticipants(control.getObject(),job.issuer(),job.maker(),job.checker());
                recheck(entry.id());
                // Lock in a consistent order across journals and ledger changes.
                for(UUID id:entry.lines().stream().map(Line::ledgerId).distinct().sorted().toList()){
                    try(var s=c.prepareStatement("SELECT id FROM bank.ledger_account WHERE id=? AND tenant_id=? AND legal_entity_id=? AND currency=? AND active FOR UPDATE")){
                        s.setObject(1,id);s.setObject(2,bank.id());s.setObject(3,entry.legalEntityId());s.setString(4,entry.currency());
                        try(var r=s.executeQuery()){if(!r.next())throw new TenantException(409,"JOURNAL_LEDGER_NOT_READY","Ledger cannot receive postings");}
                    }
                }
                try(var s=c.prepareStatement("INSERT INTO bank.journal(id,tenant_id,reference,business_date,legal_entity_id,currency,reversal_of,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)")){
                    s.setObject(1,entry.id());s.setObject(2,bank.id());s.setString(3,entry.reference());s.setObject(4,entry.businessDate());s.setObject(5,entry.legalEntityId());s.setString(6,entry.currency());s.setObject(7,entry.reversalOf());s.setString(8,payload);s.setString(9,job.maker());s.setString(10,job.issuer());s.setString(11,job.checker());s.setObject(12,job.correlationId());s.executeUpdate();
                }
                int number=0;
                for(Line line:entry.lines())try(var s=c.prepareStatement("INSERT INTO bank.journal_line(journal_id,line_number,ledger_id,side,amount) VALUES (?,?,?,?,?)")){
                    s.setObject(1,entry.id());s.setInt(2,++number);s.setObject(3,line.ledgerId());s.setString(4,line.side().name());s.setBigDecimal(5,new BigDecimal(line.amount()));s.executeUpdate();
                }

    }
    private static String money(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static TenantException unavailable(){return new TenantException(503,"JOURNAL_DATABASE_UNAVAILABLE","Journal database operation needs attention");}
}
