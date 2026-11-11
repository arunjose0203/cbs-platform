package com.cbs.reporting;

import static com.cbs.reporting.ReportModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approved templates whose lines are signed sums of chart accounts (group accounts include all
 * their descendants). Official regulatory returns are configured here from their published templates.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ReportDefinitionService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final LedgerBalances balances;
    public ReportDefinitionService(JdbcTemplate jdbc,TenantRepository tenants,LedgerBalances balances){this.jdbc=jdbc;this.tenants=tenants;this.balances=balances;}
    public void validate(Tenant bank,Definition d){
        if(bank.version()!=d.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error(409,"LEDGER_BANK_NOT_ACTIVE");
        var chart=balances.chart(bank.id());var codes=new HashSet<String>();
        for(var l:d.lines()){
            if(!codes.add(l.code()))throw error(400,"REPORT_DEFINITION_INVALID");if(l.sign()==0)throw error(400,"REPORT_DEFINITION_INVALID");
            for(var a:l.chartAccounts())if(!chart.containsKey(a))throw error(400,"REPORT_DEFINITION_ACCOUNT_UNKNOWN");
        }
    }
    public Map<String,Object> values(Tenant bank,Definition d){
        var chart=balances.chart(bank.id());
        return Map.of("code",d.code(),"name",d.name(),"basis",d.basis(),"lines",d.lines().stream().map(l->l.code()+" · "+l.label()+" = "+(l.sign()<0?"−":"+")+"("+l.chartAccounts().stream().map(a->chart.get(a).code()).sorted().reduce((x,y)->x+", "+y).orElse("")+")").toList());
    }
    public Map<String,Object> before(Tenant bank,String code){var d=definition(bank.id(),code);return d==null?Map.of():values(bank,d);}
    public void apply(Actor actor,UUID bankId,Definition d,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,d);
        jdbc.update("INSERT INTO platform.report_definition(tenant_id,code,payload,approval_id) VALUES (?,?,?::jsonb,?) ON CONFLICT(tenant_id,code) DO UPDATE SET payload=excluded.payload,approval_id=excluded.approval_id,updated_at=clock_timestamp()",bankId,d.code(),JSON.writeValueAsString(d),approvalId);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"REPORT_DEFINITION_CHANGED",bank.version()+1,bank.status(),bank.status(),d.reason(),null,null,Map.of("code",d.code(),"lines",d.lines().size()));
    }
    private Definition definition(UUID bankId,String code){var rows=jdbc.query("SELECT payload::text FROM platform.report_definition WHERE tenant_id=? AND code=?",(r,n)->JSON.readValue(r.getString(1),Definition.class),bankId,code);return rows.isEmpty()?null:rows.getFirst();}
    public List<Map<String,Object>> list(Actor actor,UUID bankId){AccessService.admin(actor);tenants.get(bankId,false);return jdbc.queryForList("SELECT code,payload->>'name' AS name,payload->>'basis' AS basis,jsonb_array_length(payload->'lines') AS lines,updated_at FROM platform.report_definition WHERE tenant_id=? ORDER BY code",bankId);}
    public Definition read(Actor actor,UUID bankId,String code){AccessService.admin(actor);tenants.get(bankId,false);var d=definition(bankId,code);if(d==null)throw error(404,"REPORT_DEFINITION_NOT_FOUND");return d;}
    public Result run(Actor actor,UUID bankId,String code,UUID entity,String currency,LocalDate from,LocalDate to){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);var d=definition(bankId,code);if(d==null)throw error(404,"REPORT_DEFINITION_NOT_FOUND");
        if(bank.legalEntities().stream().noneMatch(e->e.id().equals(entity))||currency==null||!currency.matches("[A-Z]{3}")||to==null)throw error(400,"REPORT_FILTER_INVALID");
        if(d.basis()==Basis.MOVEMENT&&(from==null||from.isAfter(to)))throw error(400,"REPORT_FILTER_INVALID");
        var snap=balances.read(bank,entity,currency,d.basis()==Basis.MOVEMENT?from:null,to);
        var lines=new ArrayList<List<String>>();
        for(var l:d.lines()){BigDecimal v=BigDecimal.ZERO;for(var a:l.chartAccounts())v=v.add(snap.amount(a));lines.add(List.of(l.code(),l.label(),FinancialStatementService.text(v.multiply(BigDecimal.valueOf(l.sign())))));}
        return new Result(d.code(),d.name(),d.basis(),entity,currency,d.basis()==Basis.MOVEMENT?from:null,to,Instant.now(),lines);
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Report definition could not be used");}
}
