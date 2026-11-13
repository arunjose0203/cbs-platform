package com.cbs.accounting;

import static com.cbs.accounting.DayCloseModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankDayCloseStore {
    private org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties;
    @org.springframework.beans.factory.annotation.Autowired void duties(org.springframework.beans.factory.ObjectProvider<com.cbs.access.BankDuties> duties){this.duties=duties;}
    /** Duty recheck for a request the bank has no receipt for yet (see BankDuties.requireAtDelivery). */
    private void recheck(java.util.UUID request){var service=duties==null?null:duties.getIfAvailable();if(service!=null)service.requireAtDelivery(request);}

    private final BankDatabaseProvisioner databases;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public BankDayCloseStore(BankDatabaseProvisioner databases){this.databases=databases;}
    private Connection connection(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    public boolean ready(Tenant bank){
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.business_day_close') IS NOT NULL AND to_regclass('bank.business_day_state') IS NOT NULL")){r.next();return r.getBoolean(1);}
        catch(SQLException e){throw unavailable();}
    }
    public LocalDate closedThrough(Tenant bank){
        try(var c=connection(bank);var s=c.createStatement();var r=s.executeQuery("SELECT closed_through FROM bank.business_day_state WHERE singleton")){
            if(!r.next())throw unavailable();return r.getObject(1,LocalDate.class);
        }catch(SQLException e){throw unavailable();}
    }
    public boolean balanced(Tenant bank){try(var c=connection(bank)){return balanced(c);}catch(SQLException e){throw unavailable();}}
    private boolean balanced(Connection c)throws SQLException{
        try(var s=c.createStatement()){
            s.setQueryTimeout(30);
            boolean fundingReady;try(var r=s.executeQuery("SELECT to_regclass('bank.funding') IS NOT NULL")){r.next();fundingReady=r.getBoolean(1);}
            boolean operations;try(var r=s.executeQuery("SELECT to_regclass('bank.customer_position') IS NOT NULL")){r.next();operations=r.getBoolean(1);}
            boolean loanFees;try(var r=s.executeQuery("SELECT to_regclass('bank.loan_fee_event') IS NOT NULL")){r.next();loanFees=r.getBoolean(1);}
            if(loanFees)try(var r=s.executeQuery("WITH latest AS (SELECT DISTINCT ON(f.contract_id,f.fee_id) f.contract_id,f.payload FROM bank.loan_fee_event f JOIN bank.contract_event e ON e.id=f.id ORDER BY f.contract_id,f.fee_id,e.event_sequence DESC),totals AS (SELECT contract_id,(payload->>'receivableLedger')::uuid ledger_id,sum((payload->>'after')::numeric) amount FROM latest GROUP BY contract_id,payload->>'receivableLedger') SELECT EXISTS(SELECT 1 FROM totals t LEFT JOIN bank.ledger_balance b ON b.ledger_id=t.ledger_id WHERE t.amount<>coalesce(b.debit-b.credit,0))")){r.next();if(r.getBoolean(1))return false;}
            boolean contracts;try(var r=s.executeQuery("SELECT to_regclass('bank.contract_event') IS NOT NULL")){r.next();contracts=r.getBoolean(1);}
            if(contracts)try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM bank.contract c LEFT JOIN LATERAL (SELECT payload->'after' p FROM bank.contract_event e WHERE e.contract_id=c.id ORDER BY event_sequence DESC LIMIT 1) x ON true LEFT JOIN bank.ledger_balance principal ON principal.ledger_id=c.principal_ledger LEFT JOIN bank.ledger_balance interest ON interest.ledger_id=c.interest_ledger WHERE x.p IS NULL OR coalesce(principal.credit-principal.debit,0)*CASE WHEN c.payload->'terms'->>'type'='TERM_DEPOSIT' THEN 1 ELSE -1 END<>(x.p->>'principal')::numeric OR coalesce(interest.credit-interest.debit,0)*CASE WHEN c.payload->'terms'->>'type'='TERM_DEPOSIT' THEN 1 ELSE -1 END<>(x.p->>'interest')::numeric)")){r.next();if(r.getBoolean(1))return false;}
            if(operations)try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.posted<>coalesce(l.credit,0)-coalesce(l.debit,0) OR p.posted<0 OR p.held>p.posted)")){r.next();if(r.getBoolean(1))return false;}
            if(fundingReady&&!operations)try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM bank.account_ledger a LEFT JOIN bank.ledger_balance l ON l.ledger_id=a.ledger_id WHERE coalesce(l.credit,0)-coalesce(l.debit,0)<>coalesce((SELECT sum(f.amount) FROM bank.funding f WHERE f.account_id=a.account_id),0))")){r.next();if(r.getBoolean(1))return false;}
            try(var r=s.executeQuery("""
                WITH totals AS (SELECT ledger_id,sum(CASE WHEN side='DEBIT' THEN amount ELSE 0 END) dr,
                sum(CASE WHEN side='CREDIT' THEN amount ELSE 0 END) cr FROM bank.journal_line GROUP BY ledger_id)
                SELECT NOT EXISTS(SELECT 1 FROM bank.ledger_account a LEFT JOIN totals t ON t.ledger_id=a.id
                LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id
                WHERE coalesce(t.dr,0)<>coalesce(b.debit,0) OR coalesce(t.cr,0)<>coalesce(b.credit,0))
                AND NOT EXISTS(SELECT 1 FROM bank.ledger_account a JOIN bank.ledger_balance b ON b.ledger_id=a.id
                GROUP BY a.legal_entity_id,a.currency HAVING sum(b.debit-b.credit)<>0)
                """)){r.next();return r.getBoolean(1);}
        }
    }
    public List<Total> close(Tenant bank,Dispatch job){
        if(!bank.id().equals(job.bankId()))throw new TenantException(409,"DATABASE_OWNERSHIP_MISMATCH","Wrong bank route");
        try(var c=connection(bank)){
            c.setAutoCommit(false);
            try{
                // Journal inserts hold a shared lock on this same row until commit.
                LocalDate closed;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT closed_through FROM bank.business_day_state WHERE singleton FOR UPDATE")){
                    if(!r.next())throw unavailable();closed=r.getObject(1,LocalDate.class);
                }
                try(var s=c.prepareStatement("SELECT * FROM bank.business_day_close WHERE request_id=?")){
                    s.setObject(1,job.requestId());try(var r=s.executeQuery()){if(r.next()){
                        if(!r.getObject("business_date",LocalDate.class).equals(job.businessDate())||!r.getObject("next_business_date",LocalDate.class).equals(job.nextBusinessDate()))throw unavailable();
                        var totals=JSON.readValue(r.getString("totals"),new TypeReference<List<Total>>(){});c.commit();return totals;
                    }}
                }
                if(closed!=null&&!job.businessDate().isAfter(closed))throw new TenantException(409,"BUSINESS_DAY_CLOSED","Day already closed");
                recheck(job.requestId());
                try(var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.journal WHERE business_date>?)")){
                    s.setObject(1,job.businessDate());try(var r=s.executeQuery()){r.next();if(r.getBoolean(1))throw new TenantException(409,"DAY_CLOSE_FUTURE_POSTINGS","Later postings exist");}
                }
                if(!balanced(c))throw new TenantException(409,"DAY_CLOSE_UNRECONCILED","Balance differences require investigation");
                List<Total> totals=new ArrayList<>();
                try(var s=c.createStatement();var r=s.executeQuery("SELECT a.legal_entity_id,a.currency,sum(greatest(coalesce(b.debit,0)-coalesce(b.credit,0),0)) dr,sum(greatest(coalesce(b.credit,0)-coalesce(b.debit,0),0)) cr FROM bank.ledger_account a LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id GROUP BY a.legal_entity_id,a.currency ORDER BY a.legal_entity_id,a.currency")){
                    while(r.next())totals.add(new Total(r.getString(1),r.getString(2),r.getBigDecimal(3).stripTrailingZeros().toPlainString(),r.getBigDecimal(4).stripTrailingZeros().toPlainString()));
                }
                try(var s=c.prepareStatement("INSERT INTO bank.business_day_close(request_id,business_date,next_business_date,maker_subject,reviewer_issuer,reviewer_subject,correlation_id,totals) VALUES (?,?,?,?,?,?,?,?::jsonb)")){
                    s.setObject(1,job.requestId());s.setObject(2,job.businessDate());s.setObject(3,job.nextBusinessDate());s.setString(4,job.maker());s.setString(5,job.issuer());s.setString(6,job.checker());s.setObject(7,job.correlationId());s.setString(8,JSON.writeValueAsString(totals));s.executeUpdate();
                }
                try(var s=c.prepareStatement("UPDATE bank.business_day_state SET closed_through=? WHERE singleton")){s.setObject(1,job.businessDate());s.executeUpdate();}
                c.commit();return totals;
            }catch(Exception e){c.rollback();throw e;}
        }catch(TenantException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static TenantException unavailable(){return new TenantException(503,"DAY_CLOSE_DATABASE_UNAVAILABLE","Business day database operation needs attention");}
}
