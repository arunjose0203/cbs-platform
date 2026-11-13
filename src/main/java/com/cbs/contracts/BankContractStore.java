package com.cbs.contracts;

import static com.cbs.contracts.ContractModels.*;
import com.cbs.accounts.AccountModels;
import com.cbs.accounting.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankContractStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final BankDatabaseProvisioner databases;
    private final BankJournalStore journals;
    public BankContractStore(BankDatabaseProvisioner databases,BankJournalStore journals){this.databases=databases;this.journals=journals;}
    public boolean ready(Tenant bank){try(var c=open(bank);var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.loan_fee_event') IS NOT NULL")){r.next();return r.getBoolean(1);}catch(SQLException e){throw unavailable();}}
    public boolean posted(Tenant bank,UUID id){try(var c=open(bank);var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM bank.contract_event WHERE id=?)")){s.setObject(1,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}catch(SQLException e){throw unavailable();}}
    public Position position(Tenant bank,UUID id){try(var c=open(bank)){return position(c,id);}catch(SQLException e){throw unavailable();}}
    private Position position(Connection c,UUID id)throws SQLException{try(var s=c.prepareStatement("SELECT payload->'after' FROM bank.contract_event WHERE contract_id=? ORDER BY event_sequence DESC LIMIT 1")){s.setObject(1,id);try(var r=s.executeQuery()){if(!r.next())throw new TenantException(409,"CONTRACT_NOT_POSTED","Post the opening instruction first");return JSON.readValue(r.getString(1),Position.class);}}}
    public UUID latestUnreversed(Tenant bank,UUID contractId) {try(var c=open(bank)){return latestUnreversed(c,contractId);}catch(SQLException e){throw unavailable();}}
    private UUID latestUnreversed(Connection c,UUID contractId)throws SQLException {
        try(var s=c.prepareStatement("SELECT e.id FROM bank.contract_event e WHERE e.contract_id=? AND e.kind<>'REVERSE' AND NOT EXISTS(SELECT 1 FROM bank.contract_reversal r WHERE r.original_id=e.id) ORDER BY e.event_sequence DESC LIMIT 1")){s.setObject(1,contractId);try(var r=s.executeQuery()){return r.next()?r.getObject(1,UUID.class):null;}}
    }
    public void post(Tenant bank,Dispatch dispatch){
        var p=dispatch.posting();var d=p.contract();
        if(!bank.id().equals(d.bankId()))throw unavailable();
        try(var c=open(bank)){c.setAutoCommit(false);try{
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,30))")){s.setString(1,d.id().toString());s.execute();}
            String payload=JSON.writeValueAsString(p);
            try(var s=c.prepareStatement("SELECT payload=?::jsonb FROM bank.contract_event WHERE id=?")){s.setString(1,payload);s.setObject(2,p.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getBoolean(1))throw unavailable();c.commit();return;}}}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT closed_through FROM bank.business_day_state WHERE singleton FOR SHARE")){
                if(!r.next())throw unavailable();var closed=r.getObject(1,java.time.LocalDate.class);if(closed!=null&&!p.businessDate().isAfter(closed))throw new TenantException(409,"BUSINESS_DAY_CLOSED","Contract day is closed");
            }
            if(p.kind()==Kind.REVERSE&&!p.originalEventId().equals(latestUnreversed(c,d.id())))throw new TenantException(409,"CONTRACT_CORRECTION_DEPENDENCIES","Reverse later contract events first");
            try(var s=c.prepareStatement("SELECT payload FROM bank.account WHERE id=? AND tenant_id=? FOR UPDATE")){s.setObject(1,d.account().id());s.setObject(2,bank.id());try(var r=s.executeQuery()){if(!r.next()||!d.account().equals(JSON.readValue(r.getString(1),AccountModels.Definition.class)))throw new TenantException(409,"ACCOUNT_RECONCILIATION_REQUIRED","Account changed");}}
            if(p.kind()==Kind.OPEN){
                try(var s=c.prepareStatement("INSERT INTO bank.contract(id,tenant_id,account_id,principal_ledger,interest_ledger,payload) VALUES (?,?,?,?,?,?::jsonb)")){s.setObject(1,d.id());s.setObject(2,bank.id());s.setObject(3,d.account().id());s.setObject(4,d.terms().principalLedger());s.setObject(5,d.terms().accruedInterestLedger());s.setString(6,JSON.writeValueAsString(d));s.executeUpdate();}
            }else if(!position(c,d.id()).equals(p.before()))throw new TenantException(409,"CONTRACT_POSITION_CHANGED","Refresh the contract position");
            try(var s=c.prepareStatement("SELECT p.posted,p.held,coalesce(l.credit-l.debit,0) gl FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=?")){s.setObject(1,d.account().id());try(var r=s.executeQuery()){if(!r.next()||r.getBigDecimal(1).compareTo(r.getBigDecimal(3))!=0)throw new TenantException(409,"FUNDING_UNRECONCILED","Customer balance mismatch");if(decimal(p.customerMovement()).signum()<0&&r.getBigDecimal(1).subtract(r.getBigDecimal(2)).subtract(decimal(d.account().terms().minimumBalance())).add(decimal(p.customerMovement())).signum()<0)throw new TenantException(409,"INSUFFICIENT_AVAILABLE_BALANCE","Insufficient available funds");}}
            if(p.journal()!=null)journals.post(c,bank,new JournalModels.Dispatch(p.journal(),dispatch.maker(),dispatch.issuer(),dispatch.checker(),dispatch.correlationId()));
            try(var s=c.prepareStatement("INSERT INTO bank.contract_event(id,contract_id,kind,journal_id,payload) VALUES (?,?,?,?,?::jsonb)")){s.setObject(1,p.id());s.setObject(2,d.id());s.setString(3,p.kind().name());s.setObject(4,p.journal()==null?null:p.journal().id());s.setString(5,payload);s.executeUpdate();}
            if(p.feeServicing()!=null)try(var s=c.prepareStatement("INSERT INTO bank.loan_fee_event(id,contract_id,fee_id,payload) VALUES (?,?,?,?::jsonb)")){s.setObject(1,p.id());s.setObject(2,d.id());s.setObject(3,p.feeServicing().feeId());s.setString(4,JSON.writeValueAsString(p.feeServicing()));s.executeUpdate();}
            if(p.loanServicing()!=null)try(var s=c.prepareStatement("INSERT INTO bank.loan_risk_event(id,contract_id,payload) VALUES (?,?,?::jsonb)")){s.setObject(1,p.id());s.setObject(2,d.id());s.setString(3,JSON.writeValueAsString(p.loanServicing()));s.executeUpdate();}
            if(p.kind()==Kind.RESTRUCTURE)try(var s=c.prepareStatement("INSERT INTO bank.contract_term_revision(id,contract_id,terms) VALUES (?,?,?::jsonb)")){s.setObject(1,p.id());s.setObject(2,d.id());s.setString(3,JSON.writeValueAsString(p.loanServicing().renewedTerms()));s.executeUpdate();}
            if(p.kind()==Kind.RENEW)try(var s=c.prepareStatement("INSERT INTO bank.contract_term_revision(id,contract_id,terms) VALUES (?,?,?::jsonb)")){s.setObject(1,p.id());s.setObject(2,d.id());s.setString(3,JSON.writeValueAsString(p.servicing().renewedTerms()));s.executeUpdate();}
            if(p.kind()==Kind.REVERSE)try(var s=c.prepareStatement("INSERT INTO bank.contract_reversal(id,original_id,contract_id) VALUES (?,?,?)")){s.setObject(1,p.id());s.setObject(2,p.originalEventId());s.setObject(3,d.id());s.executeUpdate();}
            if(decimal(p.customerMovement()).signum()!=0)try(var s=c.prepareStatement("INSERT INTO bank.contract_entry(event_id,account_id,amount) VALUES (?,?,?)")){s.setObject(1,p.id());s.setObject(2,d.account().id());s.setBigDecimal(3,decimal(p.customerMovement()));s.executeUpdate();}
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
        catch(TenantException e){throw e;}catch(Exception e){
            // Type and SQL state only: never the payload or connection details.
            org.slf4j.LoggerFactory.getLogger(BankContractStore.class).warn("Contract posting failed for bank {}: {} {}",bank.id(),e.getClass().getSimpleName(),
                e instanceof java.sql.SQLException sql?sql.getSQLState():"");
            throw unavailable();}
    }
    private Connection open(Tenant bank){return databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());}
    private static TenantException unavailable(){return new TenantException(503,"CONTRACT_DATABASE_UNAVAILABLE","Contract posting could not be confirmed; retry safely");}
}
