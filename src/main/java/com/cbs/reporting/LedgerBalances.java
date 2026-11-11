package com.cbs.reporting;

import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Tenant;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads ledger balances or movements from posted journals in one read-only snapshot, and the
 * chart of accounts they roll up to. Balances are rebuilt from journal lines, so any past date works.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class LedgerBalances {
    public record Chart(UUID id,String code,String name,String category,String normal,String kind,UUID parent) {}
    /** Net debit-minus-credit per chart account (posting accounts only), for one entity and currency. */
    public record Snapshot(Map<UUID,Chart> chart,Map<UUID,BigDecimal> net) {
        /** Amount in the account's normal-balance direction, including all descendants. */
        public BigDecimal amount(UUID account){
            var c=chart.get(account);if(c==null)return BigDecimal.ZERO;var total=net.getOrDefault(account,BigDecimal.ZERO);
            for(var child:chart.values())if(account.equals(child.parent()))total=total.add(raw(child.id()));
            return c.normal().equals("DEBIT")?total:total.negate();
        }
        private BigDecimal raw(UUID account){var total=net.getOrDefault(account,BigDecimal.ZERO);for(var child:chart.values())if(account.equals(child.parent()))total=total.add(raw(child.id()));return total;}
        public BigDecimal category(String category){BigDecimal t=BigDecimal.ZERO;for(var e:net.entrySet()){var c=chart.get(e.getKey());if(c!=null&&c.category().equals(category))t=t.add(e.getValue());}return t;}
    }
    private final JdbcTemplate jdbc;private final BankDatabaseProvisioner databases;
    public LedgerBalances(JdbcTemplate jdbc,BankDatabaseProvisioner databases){this.jdbc=jdbc;this.databases=databases;}
    public Map<UUID,Chart> chart(UUID bankId){
        var map=new LinkedHashMap<UUID,Chart>();
        jdbc.query("SELECT id,code,name,category,normal_balance,kind,parent_id FROM platform.chart_account WHERE tenant_id=? ORDER BY code",r->{map.put(r.getObject(1,UUID.class),new Chart(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,UUID.class)));},bankId);
        return map;
    }
    /**
     * Balance as at {@code to} (from null) or movement between {@code from} and {@code to}.
     * Movements exclude year-end closing journals so a closed year still shows its income and expenses.
     */
    public Snapshot read(Tenant bank,UUID entity,String currency,LocalDate from,LocalDate to){
        var chart=chart(bank.id());var ledgerChart=new HashMap<UUID,UUID>();
        jdbc.query("SELECT id,chart_account_id FROM platform.ledger_registry WHERE tenant_id=? AND legal_entity_id=? AND currency=?",r->{ledgerChart.put(r.getObject(1,UUID.class),r.getObject(2,UUID.class));},bank.id(),entity,currency);
        var net=new HashMap<UUID,BigDecimal>();
        try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            boolean periods;try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.period_close_journal') IS NOT NULL")){r.next();periods=r.getBoolean(1);}
            String sql="SELECT l.ledger_id,sum(CASE WHEN l.side='DEBIT' THEN l.amount ELSE -l.amount END) FROM bank.journal_line l JOIN bank.journal j ON j.id=l.journal_id WHERE j.legal_entity_id=? AND j.currency=? AND j.business_date<=?"
                +(from==null?"":" AND j.business_date>=?"+(periods?" AND NOT EXISTS(SELECT 1 FROM bank.period_close_journal p WHERE p.journal_id=j.id)":""))+" GROUP BY l.ledger_id";
            try(var s=c.prepareStatement(sql)){s.setObject(1,entity);s.setString(2,currency);s.setObject(3,to);if(from!=null)s.setObject(4,from);
                try(var r=s.executeQuery()){while(r.next()){var account=ledgerChart.get(r.getObject(1,UUID.class));if(account!=null)net.merge(account,r.getBigDecimal(2),BigDecimal::add);}}}
            c.commit();
        }catch(SQLException e){throw new TenantException(503,"REPORT_DATABASE_UNAVAILABLE","Report could not be read");}
        return new Snapshot(chart,net);
    }
}
