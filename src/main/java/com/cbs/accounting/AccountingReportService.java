package com.cbs.accounting;

import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Reports read one consistent bank snapshot; they never repair or write accounting data. */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class AccountingReportService {
    public enum Kind { TRIAL_BALANCE, LEDGER_STATEMENT, RECONCILIATION }
    public record Report(Kind kind,UUID bankId,String bankName,UUID legalEntityId,String legalEntityName,
            String currency,LocalDate fromDate,LocalDate toDate,Instant generatedAt,List<String> columns,
            List<List<String>> rows,Map<String,String> summary,boolean reconciled) {}
    private static final int MAX_ROWS=10000;
    private final TenantRepository tenants;
    private final BankDatabaseProvisioner databases;
    public AccountingReportService(TenantRepository tenants,BankDatabaseProvisioner databases){this.tenants=tenants;this.databases=databases;}
    public Report generate(Actor actor,UUID bankId,Kind kind,UUID entityId,String currency,LocalDate from,LocalDate to,UUID ledgerId){
        AccessService.admin(actor);
        Tenant bank=tenants.get(bankId,false);
        if(!List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status()))throw error(409,"JOURNAL_STORAGE_NOT_READY");
        var entity=bank.legalEntities().stream().filter(e->e.id().equals(entityId)).findFirst().orElseThrow(()->error(400,"LEGAL_ENTITY_NOT_IN_BANK"));
        if(currency==null||!currency.matches("[A-Z]{3}"))throw error(400,"REPORT_FILTER_INVALID");
        try{Currency.getInstance(currency);}catch(IllegalArgumentException e){throw error(400,"REPORT_FILTER_INVALID");}
        if(kind!=Kind.RECONCILIATION&&(to==null||to.getYear()<1900||to.getYear()>9999))throw error(400,"REPORT_FILTER_INVALID");
        if(kind==Kind.LEDGER_STATEMENT&&(ledgerId==null||from==null||from.getYear()<1900||from.isAfter(to)))throw error(400,"REPORT_FILTER_INVALID");
        try(Connection c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('bank.journal_line') IS NOT NULL AND to_regclass('bank.ledger_balance') IS NOT NULL")){
                r.next();if(!r.getBoolean(1))throw error(409,"JOURNAL_STORAGE_NOT_READY");
            }
            List<List<String>> rows=new ArrayList<>();Map<String,String> summary=new LinkedHashMap<>();List<String> columns;
            boolean reconciled=true;
            if(kind==Kind.LEDGER_STATEMENT){
                columns=List.of("Business date","Reference","Narration","Journal ID","Reversal of","Line","Debit","Credit","Running net debit");
                try(var s=statement(c,"SELECT code,name FROM bank.ledger_account WHERE tenant_id=? AND legal_entity_id=? AND currency=? AND id=?",bank.id(),entityId,currency,ledgerId);var r=s.executeQuery()){
                    if(!r.next())throw error(404,"LEDGER_NOT_FOUND");summary.put("Ledger account",r.getString(1)+" — "+r.getString(2));
                }
                BigDecimal opening;
                try(var s=statement(c,"SELECT coalesce(sum(CASE WHEN l.side='DEBIT' THEN l.amount ELSE -l.amount END),0) FROM bank.journal_line l JOIN bank.journal j ON j.id=l.journal_id WHERE j.tenant_id=? AND j.legal_entity_id=? AND j.currency=? AND l.ledger_id=? AND j.business_date<?",bank.id(),entityId,currency,ledgerId,from);var r=s.executeQuery()){r.next();opening=r.getBigDecimal(1);}
                BigDecimal running=opening,debit=BigDecimal.ZERO,credit=BigDecimal.ZERO;
                try(var s=statement(c,"SELECT j.business_date,j.reference,j.payload->>'narration' narration,j.id,j.reversal_of,l.line_number,l.side,l.amount FROM bank.journal_line l JOIN bank.journal j ON j.id=l.journal_id WHERE j.tenant_id=? AND j.legal_entity_id=? AND j.currency=? AND l.ledger_id=? AND j.business_date BETWEEN ? AND ? ORDER BY j.business_date,j.posted_at,j.id,l.line_number LIMIT 10001",bank.id(),entityId,currency,ledgerId,from,to);var r=s.executeQuery()){
                    while(r.next()){
                        BigDecimal amount=r.getBigDecimal("amount"),dr=r.getString("side").equals("DEBIT")?amount:BigDecimal.ZERO,cr=r.getString("side").equals("CREDIT")?amount:BigDecimal.ZERO;
                        debit=debit.add(dr);credit=credit.add(cr);running=running.add(dr).subtract(cr);
                        add(rows,List.of(r.getString("business_date"),r.getString("reference"),Objects.toString(r.getString("narration"),""),r.getString("id"),Objects.toString(r.getString("reversal_of"),""),r.getString("line_number"),money(dr),money(cr),money(running)));
                    }
                }
                summary.put("Opening net debit",money(opening));summary.put("Period debits",money(debit));summary.put("Period credits",money(credit));summary.put("Closing net debit",money(running));
            }else{
                boolean check=kind==Kind.RECONCILIATION;
                columns=check?List.of("Ledger code","Ledger name","Journal debits","Stored debits","Debit difference","Journal credits","Stored credits","Credit difference","Result"):List.of("Ledger code","Ledger name","Total debits","Total credits","Debit balance","Credit balance");
                String sql="""
                    WITH totals AS (
                      SELECT l.ledger_id,sum(CASE WHEN l.side='DEBIT' THEN l.amount ELSE 0 END) dr,
                      sum(CASE WHEN l.side='CREDIT' THEN l.amount ELSE 0 END) cr
                      FROM bank.journal_line l JOIN bank.journal j ON j.id=l.journal_id
                      WHERE j.tenant_id=? AND j.legal_entity_id=? AND j.currency=?
                    """+(check?"":" AND j.business_date<=? ")+"""
                      GROUP BY l.ledger_id)
                    SELECT a.code,a.name,coalesce(t.dr,0) dr,coalesce(t.cr,0) cr,
                    coalesce(b.debit,0) stored_dr,coalesce(b.credit,0) stored_cr
                    FROM bank.ledger_account a LEFT JOIN totals t ON t.ledger_id=a.id
                    LEFT JOIN bank.ledger_balance b ON b.ledger_id=a.id
                    WHERE a.tenant_id=? AND a.legal_entity_id=? AND a.currency=? ORDER BY a.code,a.id LIMIT 10001
                    """;
                Object[] args=check?new Object[]{bank.id(),entityId,currency,bank.id(),entityId,currency}:new Object[]{bank.id(),entityId,currency,to,bank.id(),entityId,currency};
                BigDecimal totalDr=BigDecimal.ZERO,totalCr=BigDecimal.ZERO,turnDr=BigDecimal.ZERO,turnCr=BigDecimal.ZERO;int mismatches=0;
                try(var s=statement(c,sql,args);var r=s.executeQuery()){
                    while(r.next()){
                        BigDecimal dr=r.getBigDecimal("dr"),cr=r.getBigDecimal("cr"),net=dr.subtract(cr);
                        turnDr=turnDr.add(dr);turnCr=turnCr.add(cr);totalDr=totalDr.add(net.max(BigDecimal.ZERO));totalCr=totalCr.add(net.negate().max(BigDecimal.ZERO));
                        if(check){
                            BigDecimal sd=r.getBigDecimal("stored_dr"),sc=r.getBigDecimal("stored_cr"),dd=sd.subtract(dr),dc=sc.subtract(cr);
                            boolean match=dd.signum()==0&&dc.signum()==0;if(!match)mismatches++;
                            add(rows,List.of(r.getString("code"),r.getString("name"),money(dr),money(sd),money(dd),money(cr),money(sc),money(dc),match?"MATCH":"MISMATCH"));
                        }else add(rows,List.of(r.getString("code"),r.getString("name"),money(dr),money(cr),money(net.max(BigDecimal.ZERO)),money(net.negate().max(BigDecimal.ZERO))));
                    }
                }
                if(check){summary.put("Accounts checked",String.valueOf(rows.size()));summary.put("Mismatched accounts",String.valueOf(mismatches));reconciled=mismatches==0;}
                else{summary.put("Total debits",money(turnDr));summary.put("Total credits",money(turnCr));summary.put("Debit balance",money(totalDr));summary.put("Credit balance",money(totalCr));summary.put("Difference",money(totalDr.subtract(totalCr)));reconciled=totalDr.compareTo(totalCr)==0;}
            }
            c.commit();return new Report(kind,bankId,bank.displayName(),entityId,entity.legalName(),currency,kind==Kind.LEDGER_STATEMENT?from:null,kind==Kind.RECONCILIATION?null:to,Instant.now(),columns,rows,summary,reconciled);
        }catch(TenantException e){throw e;}catch(SQLException e){throw error(503,"REPORT_DATABASE_UNAVAILABLE");}
    }
    private static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException{
        var s=c.prepareStatement(sql);s.setQueryTimeout(30);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;
    }
    private static void add(List<List<String>> rows,List<String> row){if(rows.size()>=MAX_ROWS)throw error(422,"REPORT_TOO_LARGE");rows.add(row);}
    private static String money(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static TenantException error(int status,String code){return new TenantException(status,code,"Accounting report could not be generated");}
}
