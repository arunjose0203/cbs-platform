package com.cbs.reporting;

import static com.cbs.reporting.ReportModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Balance sheet and income statement for one legal entity and currency, laid out by the
 * bank's chart of accounts with a comparative column. Read-only; nothing is translated
 * between currencies.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class FinancialStatementService {
    private final TenantRepository tenants;private final LedgerBalances balances;
    public FinancialStatementService(TenantRepository tenants,LedgerBalances balances){this.tenants=tenants;this.balances=balances;}

    public Report balanceSheet(Actor actor,UUID bankId,UUID entity,String currency,LocalDate asOf,LocalDate compareTo){
        AccessService.admin(actor);var bank=bank(bankId);var name=entity(bank,entity);check(currency,asOf);
        var compare=compareTo==null?asOf.minusYears(1):compareTo;check(currency,compare);
        var now=balances.read(bank,entity,currency,null,asOf);var then=balances.read(bank,entity,currency,null,compare);
        var rows=new ArrayList<Row>();var totals=new LinkedHashMap<String,String>();
        var assets=section(rows,"ASSET","Assets",now,then);var liabilities=section(rows,"LIABILITY","Liabilities",now,then);var equity=sectionNoTotal(rows,"EQUITY",now,then);
        // Income and expenses not yet moved to retained earnings by a year-end close.
        var result=profit(now);var priorResult=profit(then);
        rows.add(new Row("EQUITY",1,"","Current period result (not yet closed)",text(result),text(priorResult),false));
        var equityNow=equity[0].add(result);var equityThen=equity[1].add(priorResult);
        rows.add(new Row("EQUITY",0,"","Total equity",text(equityNow),text(equityThen),true));
        rows.add(new Row("TOTAL",0,"","Total liabilities and equity",text(liabilities[0].add(equityNow)),text(liabilities[1].add(equityThen)),true));
        totals.put("assets",text(assets[0]));totals.put("liabilities",text(liabilities[0]));totals.put("equity",text(equityNow));
        boolean balanced=assets[0].compareTo(liabilities[0].add(equityNow))==0&&assets[1].compareTo(liabilities[1].add(equityThen))==0;
        return new Report(Statement.BALANCE_SHEET,bank.displayName(),entity,name,currency,null,asOf,null,compare,Instant.now(),rows,totals,balanced);
    }
    public Report incomeStatement(Actor actor,UUID bankId,UUID entity,String currency,LocalDate from,LocalDate to,LocalDate compareFrom,LocalDate compareTo){
        AccessService.admin(actor);var bank=bank(bankId);var name=entity(bank,entity);check(currency,to);
        if(from==null||from.isAfter(to)||from.isBefore(to.minusYears(5)))throw error(400,"REPORT_FILTER_INVALID");
        var cf=compareFrom==null?from.minusYears(1):compareFrom;var ct=compareTo==null?to.minusYears(1):compareTo;if(cf.isAfter(ct))throw error(400,"REPORT_FILTER_INVALID");
        var now=balances.read(bank,entity,currency,from,to);var then=balances.read(bank,entity,currency,cf,ct);
        var rows=new ArrayList<Row>();var income=section(rows,"INCOME","Income",now,then);var expense=section(rows,"EXPENSE","Expenses",now,then);
        var result=income[0].subtract(expense[0]);var prior=income[1].subtract(expense[1]);
        rows.add(new Row("TOTAL",0,"",result.signum()>=0?"Net profit":"Net loss",text(result),text(prior),true));
        var totals=new LinkedHashMap<String,String>();totals.put("income",text(income[0]));totals.put("expense",text(expense[0]));totals.put("netResult",text(result));
        return new Report(Statement.INCOME_STATEMENT,bank.displayName(),entity,name,currency,from,to,cf,ct,Instant.now(),rows,totals,true);
    }

    private BigDecimal[] section(List<Row> rows,String category,String title,LedgerBalances.Snapshot now,LedgerBalances.Snapshot then){
        var t=sectionNoTotal(rows,category,now,then);rows.add(new Row(category,0,"","Total "+title.toLowerCase(Locale.ROOT),text(t[0]),text(t[1]),true));return t;
    }
    /** Adds the chart tree for one category, skipping branches that are zero in both columns. */
    private BigDecimal[] sectionNoTotal(List<Row> rows,String category,LedgerBalances.Snapshot now,LedgerBalances.Snapshot then){
        var roots=now.chart().values().stream().filter(c->c.category().equals(category)&&(c.parent()==null||!now.chart().containsKey(c.parent())||!now.chart().get(c.parent()).category().equals(category))).sorted(Comparator.comparing(LedgerBalances.Chart::code)).toList();
        BigDecimal a=BigDecimal.ZERO,b=BigDecimal.ZERO;
        for(var r:roots){walk(rows,r,1,now,then);a=a.add(now.amount(r.id()));b=b.add(then.amount(r.id()));}
        // Category totals use the category's natural sign.
        return new BigDecimal[]{a,b};
    }
    private void walk(List<Row> rows,LedgerBalances.Chart node,int level,LedgerBalances.Snapshot now,LedgerBalances.Snapshot then){
        var a=now.amount(node.id());var b=then.amount(node.id());if(a.signum()==0&&b.signum()==0)return;
        rows.add(new Row(node.category(),level,node.code(),node.name(),text(a),text(b),false));
        if(level<6)now.chart().values().stream().filter(c->node.id().equals(c.parent())).sorted(Comparator.comparing(LedgerBalances.Chart::code)).forEach(c->walk(rows,c,level+1,now,then));
    }
    /** Credit-positive result of income less expenses held in the books. */
    private static BigDecimal profit(LedgerBalances.Snapshot s){return s.category("INCOME").add(s.category("EXPENSE")).negate();}
    private Tenant bank(UUID id){var b=tenants.get(id,false);if(!List.of(Status.ACTIVE,Status.SUSPENDED).contains(b.status()))throw error(409,"JOURNAL_STORAGE_NOT_READY");return b;}
    private static String entity(Tenant bank,UUID id){return bank.legalEntities().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->error(400,"LEGAL_ENTITY_NOT_IN_BANK")).legalName();}
    private static void check(String currency,LocalDate date){
        if(currency==null||!currency.matches("[A-Z]{3}")||date==null||date.getYear()<1900||date.getYear()>9999)throw error(400,"REPORT_FILTER_INVALID");
        try{Currency.getInstance(currency);}catch(IllegalArgumentException e){throw error(400,"REPORT_FILTER_INVALID");}
    }
    static String text(BigDecimal v){return v.stripTrailingZeros().toPlainString();}
    static TenantException error(int status,String code){return new TenantException(status,code,"Report could not be produced");}
}
