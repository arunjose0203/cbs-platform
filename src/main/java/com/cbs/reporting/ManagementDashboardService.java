package com.cbs.reporting;

import com.cbs.access.AccessService;
import com.cbs.contracts.ContractModels;
import com.cbs.contracts.LoanArrears;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Management view of one bank on its current business date: deposits, loan quality,
 * cash holdings and work waiting. Amounts are per currency and never converted.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ManagementDashboardService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static final int LOAN_LIMIT=5000;
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final BankDatabaseProvisioner databases;private final com.cbs.reference.ReferenceDataService reference;
    public ManagementDashboardService(JdbcTemplate jdbc,TenantRepository tenants,BankDatabaseProvisioner databases,com.cbs.reference.ReferenceDataService reference){this.jdbc=jdbc;this.tenants=tenants;this.databases=databases;this.reference=reference;}

    public Map<String,Object> dashboard(Actor actor,UUID bankId){
        AccessService.admin(actor);var bank=tenants.get(bankId,false);var date=reference.read(bank).businessDate();
        var result=new LinkedHashMap<String,Object>();result.put("bankName",bank.displayName());result.put("businessDate",date);
        var posted=new HashMap<UUID,BigDecimal>();var cash=new HashMap<UUID,BigDecimal>();var names=new HashMap<UUID,String>();
        try(var c=databases.openOwned(bank.id(),bank.databaseBinding().databaseReference())){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            if(exists(c,"bank.customer_position"))try(var s=c.createStatement();var r=s.executeQuery("SELECT account_id,posted FROM bank.customer_position")){while(r.next())posted.put(r.getObject(1,UUID.class),r.getBigDecimal(2));}
            if(exists(c,"bank.cash_position"))try(var s=c.createStatement();var r=s.executeQuery("SELECT id,balance FROM bank.cash_position")){while(r.next())cash.put(r.getObject(1,UUID.class),r.getBigDecimal(2));}
            if(exists(c,"bank.customer"))try(var s=c.createStatement();var r=s.executeQuery("SELECT id,payload->'profile'->>'fullName' FROM bank.customer")){while(r.next())names.put(r.getObject(1,UUID.class),r.getString(2));}
            c.commit();
        }catch(SQLException e){throw new TenantException(503,"REPORT_DATABASE_UNAVAILABLE","Dashboard could not be read");}

        // Deposits by product, branch and top customers.
        record Acc(UUID id,UUID customer,String customerNumber,String product,String currency,String branch){}
        var accounts=jdbc.query("SELECT a.id,a.customer_id,c.customer_number,d.payload->'terms'->>'code',d.payload->'terms'->>'currency',coalesce(b.display_name,'Unassigned') FROM platform.account_registry a JOIN platform.account_delivery d ON d.request_id=a.request_id JOIN platform.customer_registry c ON c.id=a.customer_id LEFT JOIN platform.account_branch ab ON ab.account_id=a.id LEFT JOIN platform.branch b ON b.id=ab.branch_id WHERE a.tenant_id=? AND a.state<>'CLOSED'",
            (r,n)->new Acc(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),bankId);
        var byProduct=new TreeMap<String,Agg>();var byBranch=new TreeMap<String,Agg>();var byCustomer=new HashMap<String,BigDecimal>();var customerNumber=new HashMap<UUID,String>();var totals=new TreeMap<String,Agg>();
        for(var a:accounts){var p=posted.get(a.id());if(p==null)continue;
            byProduct.computeIfAbsent(a.product()+"|"+a.currency(),k->new Agg()).add(p);byBranch.computeIfAbsent(a.branch()+"|"+a.currency(),k->new Agg()).add(p);totals.computeIfAbsent(a.currency(),k->new Agg()).add(p);
            byCustomer.merge(a.customer()+"|"+a.currency(),p,BigDecimal::add);customerNumber.put(a.customer(),a.customerNumber());}
        var top=byCustomer.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed()).limit(10).map(e->{var k=e.getKey().split("\\|");var id=UUID.fromString(k[0]);
            var total=totals.get(k[1]).sum;return Map.<String,Object>of("customerNumber",customerNumber.get(id),"name",names.getOrDefault(id,""),"currency",k[1],"balance",text(e.getValue()),"shareOfDeposits",total.signum()==0?"0":text(e.getValue().multiply(BigDecimal.valueOf(100)).divide(total,2,java.math.RoundingMode.HALF_EVEN)));}).toList();
        result.put("deposits",Map.of("totals",rows(totals,"currency"),"byProduct",rows(byProduct,"product"),"byBranch",rows(byBranch,"branch"),"topDepositors",top));

        // Loan quality from replayed posted events (the same calculation as collections).
        var contracts=jdbc.queryForList("SELECT id FROM platform.contract WHERE tenant_id=? AND kind='LOAN' AND status='ACTIVE' ORDER BY id LIMIT ?",UUID.class,bankId,LOAN_LIMIT+1);
        var buckets=new TreeMap<String,Map<String,BigDecimal>>();var counts=new TreeMap<String,Integer>();var npl=new TreeMap<String,BigDecimal>();int unreconciled=0;
        for(var id:contracts.subList(0,Math.min(contracts.size(),LOAN_LIMIT))){
            var events=jdbc.query("SELECT payload FROM platform.contract_delivery WHERE tenant_id=? AND contract_id=? AND completed ORDER BY created_at,id",(r,n)->JSON.readValue(r.getString(1),ContractModels.Posting.class),bankId,id);
            if(events.isEmpty())continue;String currency=events.getFirst().contract().account().terms().currency();
            LoanArrears.Report report;try{report=LoanArrears.calculate(events,date);}catch(RuntimeException e){unreconciled++;continue;}
            if(!report.reconciled())unreconciled++;counts.merge(currency,1,Integer::sum);
            var map=buckets.computeIfAbsent(currency,k->new LinkedHashMap<>());
            for(var b:report.buckets().entrySet()){var v=new BigDecimal(b.getValue().total());map.merge(b.getKey(),v,BigDecimal::add);if(b.getKey().equals("91_180")||b.getKey().equals("181_PLUS"))npl.merge(currency,v,BigDecimal::add);}
        }
        var loans=new ArrayList<Map<String,Object>>();
        for(var e:buckets.entrySet()){BigDecimal total=e.getValue().values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);var b=new LinkedHashMap<String,String>();e.getValue().forEach((k,v)->b.put(k,text(v)));
            loans.add(Map.of("currency",e.getKey(),"loans",counts.get(e.getKey()),"outstanding",text(total),"buckets",b,"nonPerforming",text(npl.getOrDefault(e.getKey(),BigDecimal.ZERO)),"nonPerformingRatio",total.signum()==0?"0":text(npl.getOrDefault(e.getKey(),BigDecimal.ZERO).multiply(BigDecimal.valueOf(100)).divide(total,2,java.math.RoundingMode.HALF_EVEN))));}
        result.put("loans",Map.of("portfolio",loans,"truncated",contracts.size()>LOAN_LIMIT,"unreconciled",unreconciled,"nonPerformingDefinition","More than 90 days past due"));

        // Cash in vaults and tills.
        result.put("cash",jdbc.query("SELECT l.id,l.code,l.kind,l.currency,coalesce(b.display_name,'') FROM platform.cash_location l LEFT JOIN platform.branch b ON b.id=l.branch_id WHERE l.tenant_id=? AND l.applied ORDER BY b.display_name,l.code",
            (r,n)->Map.<String,Object>of("code",r.getString(2),"kind",r.getString(3),"currency",r.getString(4),"branch",r.getString(5),"balance",text(cash.getOrDefault(r.getObject(1,UUID.class),BigDecimal.ZERO))),bankId));

        // Work waiting.
        var work=new LinkedHashMap<String,Object>();
        work.put("pendingApprovals",jdbc.queryForList("SELECT action,count(*) AS count FROM platform.approval_request WHERE bank_id=? AND status='PENDING' GROUP BY action ORDER BY action",bankId));
        var failed=new LinkedHashMap<String,Long>();
        for(String table:List.of("journal_delivery","funding_delivery","account_operation","cash_delivery","contract_delivery","servicing_run","period_close"))
            failed.put(table,jdbc.queryForObject("SELECT count(*) FROM platform."+table+" WHERE tenant_id=? AND NOT completed AND failure_code IS NOT NULL",Long.class,bankId));
        work.put("failedPostings",failed);
        work.put("alerts",jdbc.queryForList("SELECT status,count(*) AS count FROM platform.notification WHERE tenant_id=? AND status<>'SENT' GROUP BY status ORDER BY status",bankId));
        work.put("openComplianceCases",jdbc.queryForObject("SELECT count(*) FROM platform.compliance_case WHERE tenant_id=? AND status<>'CLEARED'",Long.class,bankId));
        work.put("openCashExceptions",jdbc.queryForObject("SELECT count(*) FROM platform.cash_exception e WHERE e.tenant_id=? AND NOT EXISTS(SELECT 1 FROM platform.cash_exception_resolution r WHERE r.case_id=e.id)",Long.class,bankId));
        work.put("openPaymentCases",jdbc.queryForObject("SELECT count(*) FROM platform.payment_case WHERE tenant_id=? AND state NOT IN ('RESOLVED','CANCELLED')",Long.class,bankId));
        result.put("work",work);
        tenants.audit(actor,bankId,"MANAGEMENT_DASHBOARD_VIEWED",bank.version(),bank.status(),bank.status(),"Management dashboard viewed",null,null,Map.of());
        return result;
    }
    private static final class Agg{BigDecimal sum=BigDecimal.ZERO;int count;void add(BigDecimal v){sum=sum.add(v);count++;}}
    private static List<Map<String,Object>> rows(Map<String,Agg> m,String label){
        return m.entrySet().stream().map(e->{var k=e.getKey().split("\\|");var row=new LinkedHashMap<String,Object>();if(k.length==2)row.put(label,k[0]);row.put("currency",k[k.length-1]);row.put("accounts",e.getValue().count);row.put("balance",text(e.getValue().sum));return (Map<String,Object>)row;}).toList();
    }
    private static boolean exists(Connection c,String table)throws SQLException{try(var s=c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")){s.setString(1,table);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}}
    private static String text(BigDecimal v){return v.stripTrailingZeros().toPlainString();}
}
