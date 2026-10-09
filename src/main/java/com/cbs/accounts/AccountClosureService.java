package com.cbs.accounts;

import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Conservative closure: no payout, debt forgiveness or final interest settlement is implied. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountClosureService {
    private final JdbcTemplate jdbc;
    private final BankDatabaseProvisioner databases;
    public AccountClosureService(JdbcTemplate jdbc,BankDatabaseProvisioner databases){this.jdbc=jdbc;this.databases=databases;}
    public record Check(long version,UUID accountId,String posted,String held,String ledgerBalance,List<String> blockers) {
        public boolean eligible(){return blockers.isEmpty();}
    }
    public Check check(Tenant bank,AccountModels.Definition account){
        List<String> blockers=new ArrayList<>();String posted="0",held="0",ledger="0";
        if(bank.status()!=com.cbs.tenancy.TenantModels.Status.ACTIVE)blockers.add("ACCOUNT_BANK_NOT_ACTIVE");
        if(account.state()==AccountModels.State.CLOSED)blockers.add("ACCOUNT_ALREADY_CLOSED");
        try {com.cbs.accounting.DayCloseService.requireOpen(jdbc,bank.id());}
        catch(TenantException e){blockers.add(e.code());}
        try {OperationService.requireIdle(jdbc,account.id());}
        catch(TenantException e){blockers.add(e.code());}
        var bindings=jdbc.query("SELECT ledger_id FROM platform.account_ledger WHERE account_id=?",(r,n)->r.getObject(1,UUID.class),account.id());
        boolean funded=!bindings.isEmpty();
        if(funded){
            try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference());var s=c.prepareStatement("SELECT p.posted,p.held,coalesce(l.credit,0)-coalesce(l.debit,0) gl FROM bank.customer_position p LEFT JOIN bank.ledger_balance l ON l.ledger_id=p.ledger_id WHERE p.account_id=? AND p.ledger_id=?")){
                try(var schema=c.createStatement();var r=schema.executeQuery("SELECT to_regprocedure('bank.guard_account_closure()') IS NOT NULL")){r.next();if(!r.getBoolean(1))blockers.add("ACCOUNT_STORAGE_NOT_READY");}
                s.setObject(1,account.id());s.setObject(2,bindings.getFirst());try(var r=s.executeQuery()){
                    if(!r.next())blockers.add("ACCOUNT_RECONCILIATION_REQUIRED");
                    else {posted=r.getBigDecimal("posted").toPlainString();held=r.getBigDecimal("held").toPlainString();ledger=r.getBigDecimal("gl").toPlainString();
                        if(r.getBigDecimal("posted").compareTo(r.getBigDecimal("gl"))!=0)blockers.add("ACCOUNT_RECONCILIATION_REQUIRED");
                        if(r.getBigDecimal("posted").signum()!=0||r.getBigDecimal("gl").signum()!=0)blockers.add("FUNDING_BALANCE_NOT_ZERO");
                        if(r.getBigDecimal("held").signum()!=0)blockers.add("ACCOUNT_CLOSURE_HOLDS");
                    }
                }
                try(var q=c.prepareStatement("SELECT coalesce((SELECT carry_after FROM bank.servicing_event WHERE account_id=? ORDER BY business_date DESC LIMIT 1),0)<>0")){q.setObject(1,account.id());try(var r=q.executeQuery()){r.next();if(r.getBoolean(1))blockers.add("ACCOUNT_CLOSURE_SERVICING_REQUIRED");}}
            }catch(java.sql.SQLException e){throw new TenantException(503,"ACCOUNT_DATABASE_UNAVAILABLE","Closure checks could not be completed");}
            // Contract recovery, late provider returns and final servicing settlement need dedicated workflows.
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract WHERE account_id=?)",Boolean.class,account.id()))blockers.add("ACCOUNT_CLOSURE_CONTRACT_REVIEW");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_order WHERE account_id=?)",Boolean.class,account.id()))blockers.add("ACCOUNT_CLOSURE_PAYMENT_REVIEW");
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_policy WHERE tenant_id=? AND product_id=? AND product_revision=? AND legal_entity_id=?)",Boolean.class,bank.id(),account.terms().id(),account.terms().revision(),account.legalEntityId()))blockers.add("ACCOUNT_CLOSURE_SERVICING_REQUIRED");
        }
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))blockers.add("ACCOUNT_CLOSURE_SERVICING_REQUIRED");
        return new Check(bank.version(),account.id(),posted,held,ledger,List.copyOf(new LinkedHashSet<>(blockers)));
    }
    public void requireEligible(Tenant bank,AccountModels.Definition account){
        var check=check(bank,account);if(!check.eligible())throw new TenantException(409,check.blockers().getFirst(),"Resolve the account closure blockers before submitting or delivering closure");
    }
}
