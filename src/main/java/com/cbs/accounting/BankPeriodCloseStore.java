package com.cbs.accounting;

import static com.cbs.accounting.PeriodCloseModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankPeriodCloseStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final BankDatabaseProvisioner databases;private final BankJournalStore journals;
    public BankPeriodCloseStore(BankDatabaseProvisioner databases,BankJournalStore journals){this.databases=databases;this.journals=journals;}
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    public boolean ready(Tenant bank){try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.period_close') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
    /** Current debit and credit balance of every ledger, read in one snapshot. */
    public record LedgerBalance(UUID ledgerId,UUID legalEntityId,String currency,BigDecimal debit,BigDecimal credit) {}
    public List<LedgerBalance> balances(Tenant bank){
        try(var c=connection(bank);var s=c.prepareStatement("SELECT a.id,a.legal_entity_id,a.currency,coalesce(b.debit,0),coalesce(b.credit,0) FROM bank.ledger_account a LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id WHERE a.tenant_id=? ORDER BY a.id")){
            s.setObject(1,bank.id());var rows=new ArrayList<LedgerBalance>();
            try(var r=s.executeQuery()){while(r.next())rows.add(new LedgerBalance(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getBigDecimal(4),r.getBigDecimal(5)));}
            return rows;
        }catch(SQLException e){throw unavailable();}
    }
    public boolean posted(Tenant bank,UUID id){try(var c=connection(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.period_close WHERE request_id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
    /**
     * Records the close and posts any closing journals in one transaction. Ledger balances are
     * re-checked against the approved plan so a late posting can never be silently swept.
     */
    public void post(Tenant bank,Dispatch job){
        var plan=job.plan();if(!bank.id().equals(plan.bankId()))throw unavailable();
        try(var c=connection(bank)){c.setAutoCommit(false);try{
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,23))")){s.setString(1,bank.id().toString());s.execute();}
            var payload=JSON.writeValueAsString(plan);
            try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.period_close WHERE request_id=?")){s.setString(1,payload);s.setObject(2,plan.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}}
            for(var journal:plan.journals())for(var line:journal.lines()){
                // Each swept ledger must still hold exactly the balance the closing line reverses.
                try(var s=c.prepareStatement("SELECT coalesce(b.debit,0)-coalesce(b.credit,0) FROM bank.ledger_account a LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id WHERE a.id=? FOR UPDATE OF a")){s.setObject(1,line.ledgerId());try(var r=s.executeQuery()){if(!r.next())throw unavailable();var net=r.getBigDecimal(1);
                    var expected=line.side()==JournalModels.Side.CREDIT?new BigDecimal(line.amount()):new BigDecimal(line.amount()).negate();
                    if(!retained(journal,line)&&net.compareTo(expected)!=0)throw new TenantException(409,"PERIOD_CLOSE_BALANCE_CHANGED","Ledger balances changed after approval");}}
            }
            try(var s=c.prepareStatement("INSERT INTO bank.period_close(request_id,period_type,period_end,payload) VALUES (?,?,?,?::jsonb)")){s.setObject(1,plan.id());s.setString(2,plan.type().name());s.setObject(3,plan.periodEnd());s.setString(4,payload);s.executeUpdate();}
            for(var journal:plan.journals())try(var s=c.prepareStatement("INSERT INTO bank.period_close_journal(journal_id,request_id) VALUES (?,?)")){s.setObject(1,journal.id());s.setObject(2,plan.id());s.executeUpdate();}
            for(var journal:plan.journals())journals.post(c,bank,new JournalModels.Dispatch(journal,job.maker(),job.issuer(),job.checker(),job.correlationId()));
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
        catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    /** The retained-earnings line is the last line of each closing journal. */
    private static boolean retained(JournalModels.Entry journal,JournalModels.Line line){return journal.lines().getLast()==line;}
    private static TenantException unavailable(){return new TenantException(503,"PERIOD_CLOSE_DATABASE_UNAVAILABLE","Period close could not be confirmed; retry safely");}
}
