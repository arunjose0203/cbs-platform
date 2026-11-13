package com.cbs.accounts;

import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Closure eligibility. A funded account with a positive balance first needs an approved
 * CLOSURE_SETTLEMENT operation that pays out the whole balance and forfeits any sub-unit
 * interest carry; closure itself still posts nothing. Interest is settled through the last
 * completed daily servicing run (the day before closure); no pro-rata accrual for the closing
 * day, debt forgiveness, contract settlement or payment-return handling is implied.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountClosureService {
    private final JdbcTemplate jdbc;
    private final BankDatabaseProvisioner databases;
    public AccountClosureService(JdbcTemplate jdbc,BankDatabaseProvisioner databases){this.jdbc=jdbc;this.databases=databases;}
    public record Check(long version,UUID accountId,String posted,String held,String ledgerBalance,String carry,boolean settled,List<String> blockers) {
        public boolean eligible(){return blockers.isEmpty();}
    }
    public Check check(Tenant bank,AccountModels.Definition account){
        List<String> blockers=new ArrayList<>();String posted="0",held="0",ledger="0",carry="0";boolean settled=false;
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
                boolean ready;try(var schema=c.createStatement();var r=schema.executeQuery("SELECT to_regprocedure('bank.guard_account_closure()') IS NOT NULL AND to_regclass('bank.closure_settlement') IS NOT NULL")){r.next();ready=r.getBoolean(1);if(!ready)blockers.add("ACCOUNT_STORAGE_NOT_READY");}
                s.setObject(1,account.id());s.setObject(2,bindings.getFirst());try(var r=s.executeQuery()){
                    if(!r.next())blockers.add("ACCOUNT_RECONCILIATION_REQUIRED");
                    else {posted=r.getBigDecimal("posted").toPlainString();held=r.getBigDecimal("held").toPlainString();ledger=r.getBigDecimal("gl").toPlainString();
                        if(r.getBigDecimal("posted").compareTo(r.getBigDecimal("gl"))!=0)blockers.add("ACCOUNT_RECONCILIATION_REQUIRED");
                        if(r.getBigDecimal("posted").signum()!=0||r.getBigDecimal("gl").signum()!=0)blockers.add("FUNDING_BALANCE_NOT_ZERO");
                        if(r.getBigDecimal("held").signum()!=0)blockers.add("ACCOUNT_CLOSURE_HOLDS");
                    }
                }
                // A residual sub-unit carry is forfeited only by a recorded final settlement with that exact carry.
                try(var q=c.prepareStatement("SELECT coalesce((SELECT carry_after FROM bank.servicing_event WHERE account_id=? ORDER BY business_date DESC LIMIT 1),0)")){q.setObject(1,account.id());try(var r=q.executeQuery()){r.next();var value=r.getBigDecimal(1);carry=value.stripTrailingZeros().toPlainString();
                    if(ready)try(var z=c.prepareStatement("SELECT s.forfeited_carry FROM bank.closure_settlement s WHERE s.account_id=? AND NOT EXISTS(SELECT 1 FROM bank.closure_settlement_reversal r WHERE r.original_id=s.request_id)")){z.setObject(1,account.id());try(var zr=z.executeQuery()){if(zr.next()){settled=true;if(value.signum()!=0&&zr.getBigDecimal(1).compareTo(value)!=0)blockers.add("ACCOUNT_CLOSURE_CARRY_SETTLEMENT");}else if(value.signum()!=0)blockers.add("ACCOUNT_CLOSURE_CARRY_SETTLEMENT");}}
                    else if(value.signum()!=0)blockers.add("ACCOUNT_CLOSURE_CARRY_SETTLEMENT");}}
            }catch(java.sql.SQLException e){throw new TenantException(503,"ACCOUNT_DATABASE_UNAVAILABLE","Closure checks could not be completed");}
        }
        // Contract recovery: only active/unclosed contracts block closure
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.contract WHERE account_id=? AND status<>'CLOSED')",Boolean.class,account.id()))blockers.add("ACCOUNT_CLOSURE_CONTRACT_REVIEW");
        // Payment review: only in-flight payments or open investigations block closure
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.payment_order WHERE account_id=? AND state NOT IN ('SETTLED','REFUNDED','RETURNED')) OR EXISTS(SELECT 1 FROM platform.payment_case c JOIN platform.payment_order o ON o.id=c.payment_id WHERE o.account_id=? AND c.state NOT IN ('RESOLVED','CANCELLED'))",Boolean.class,account.id(),account.id()))blockers.add("ACCOUNT_CLOSURE_PAYMENT_REVIEW");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.servicing_run WHERE tenant_id=? AND NOT completed)",Boolean.class,bank.id()))blockers.add("ACCOUNT_CLOSURE_SERVICING_REQUIRED");
        return new Check(bank.version(),account.id(),posted,held,ledger,carry,settled,List.copyOf(new LinkedHashSet<>(blockers)));
    }
    /** Blockers that the final settlement itself resolves; every other blocker must be cleared first. */
    private static final Set<String> SETTLEABLE=Set.of("FUNDING_BALANCE_NOT_ZERO","ACCOUNT_CLOSURE_CARRY_SETTLEMENT");
    public Check requireSettleable(Tenant bank,AccountModels.Definition account){
        var check=check(bank,account);
        if(check.settled())throw new TenantException(409,"ACCOUNT_PENDING_CLOSURE","The account already has a final settlement and awaits closure");
        var other=check.blockers().stream().filter(b->!SETTLEABLE.contains(b)).findFirst();
        if(other.isPresent())throw new TenantException(409,other.get(),"Resolve the account closure blockers before final settlement");
        if(new java.math.BigDecimal(check.posted()).signum()<=0)throw new TenantException(409,"CLOSURE_SETTLEMENT_NOT_REQUIRED","The account has no balance to settle");
        return check;
    }
    /** After an approved (or pending) final settlement, only the closure itself may follow. */
    public static void requireNotSettling(JdbcTemplate jdbc,UUID account){
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.account_operation o WHERE o.account_id=? AND o.payload->'change'->>'kind'='CLOSURE_SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM platform.account_operation r WHERE r.account_id=? AND r.payload->'change'->>'kind'='CLOSURE_SETTLEMENT_REVERSAL' AND (r.payload->'change'->>'originalId')::uuid=o.request_id AND r.completed))",Boolean.class,account,account))
            throw new TenantException(409,"ACCOUNT_PENDING_CLOSURE","The account is pending closure after final settlement");
    }
    public void requireEligible(Tenant bank,AccountModels.Definition account){
        var check=check(bank,account);if(!check.eligible())throw new TenantException(409,check.blockers().getFirst(),"Resolve the account closure blockers before submitting or delivering closure");
    }
}
