package com.cbs.accounting;

import static com.cbs.accounting.ChartModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ChartService {
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    public ChartService(JdbcTemplate jdbc,TenantRepository tenants) { this.jdbc=jdbc;this.tenants=tenants; }
    private static final String SELECT="SELECT a.*,p.code AS parent_code FROM platform.chart_account a LEFT JOIN platform.chart_account p ON p.tenant_id=a.tenant_id AND p.id=a.parent_id";

    @Transactional
    public Chart list(Actor actor,UUID bankId,String search,Category category,Boolean active,Kind kind,int limit,int offset) {
        AccessService.admin(actor);AccessService.page(limit,offset);
        if(search.length()>100)throw error(400,"INVALID_REQUEST");
        jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",rs->{},bankId);
        Tenant bank=tenants.get(bankId,false);
        String where=" WHERE a.tenant_id=?";
        List<Object> args=new ArrayList<>();args.add(bankId);
        if(!search.isBlank()){where+=" AND position(lower(?) in lower(a.code || ' ' || a.name)) > 0";args.add(search.trim());}
        if(category!=null){where+=" AND a.category=?";args.add(category.name());}
        if(active!=null){where+=" AND a.active=?";args.add(active);}
        if(kind!=null){where+=" AND a.kind=?";args.add(kind.name());}
        long total=jdbc.queryForObject("SELECT count(*) FROM platform.chart_account a"+where,Long.class,args.toArray());
        args.add(limit);args.add(offset);
        List<Account> rows=jdbc.query(SELECT+where+" ORDER BY a.code,a.id LIMIT ? OFFSET ?",(r,n)->account(r),args.toArray());
        long[] counts=jdbc.queryForObject("SELECT count(*),count(*) FILTER (WHERE kind='GROUP'),count(*) FILTER (WHERE active) FROM platform.chart_account WHERE tenant_id=?",
                (r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},bankId);
        return new Chart(bankId,bank.version(),rows,total,counts[0],counts[1],counts[2],limit,offset);
    }
    public Account get(UUID bankId,UUID id) {
        var rows=jdbc.query(SELECT+" WHERE a.tenant_id=? AND a.id=?",(r,n)->account(r),bankId,id);
        if(rows.isEmpty())throw error(404,"CHART_ACCOUNT_NOT_FOUND");
        return rows.getFirst();
    }
    // Caller holds the tenant write lock, serializing chart and bank configuration changes.
    public void validate(Tenant bank,Change input) {
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"CHART_BANK_NOT_ACTIVE");
        if(input.accountId()!=null) {
            Account current=get(bank.id(),input.accountId());
            if(!current.code().equals(input.code()) || current.category()!=input.category() || current.kind()!=input.kind()
                    || current.normalBalance()!=input.normalBalance() || !Objects.equals(current.parentId(),input.parentId()))
                throw error(400,"CHART_STRUCTURE_IMMUTABLE");
        } else if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.chart_account WHERE tenant_id=? AND code=?)",Boolean.class,bank.id(),input.code()))
            throw error(409,"CHART_CODE_IN_USE");
        if(input.parentId()!=null) {
            Account parent=get(bank.id(),input.parentId());
            if(parent.kind()!=Kind.GROUP || parent.category()!=input.category() || (input.active()&&!parent.active()))
                throw error(400,"CHART_INVALID_PARENT");
        }
        if(!input.active() && input.accountId()!=null && jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.account_registry WHERE tenant_id=? AND liability_account_id=? AND (state<>'CLOSED' OR delivery<>'APPLIED'))",Boolean.class,bank.id(),input.accountId()))
            throw error(409,"CHART_USED_BY_ACCOUNT");
        if(!input.active() && input.accountId()!=null && jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.deposit_product WHERE tenant_id=? AND liability_account_id=? AND active)",Boolean.class,bank.id(),input.accountId()))
            throw error(409,"CHART_USED_BY_PRODUCT");
        if(!input.active() && input.accountId()!=null && jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.ledger_registry WHERE tenant_id=? AND chart_account_id=? AND (active OR delivery='PENDING'))",Boolean.class,bank.id(),input.accountId()))
            throw error(409,"CHART_USED_BY_LEDGER");
        if(!input.active() && input.accountId()!=null && jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.chart_account WHERE tenant_id=? AND parent_id=? AND active)",Boolean.class,bank.id(),input.accountId()))
            throw error(409,"CHART_ACTIVE_CHILDREN");
    }
    public Map<String,Object> before(UUID bankId,Change input) {
        return input.accountId()==null?Map.of():values(get(bankId,input.accountId()));
    }
    public Map<String,Object> values(UUID bankId,Change input) {
        return values(input.code(),input.name().trim(),input.description().trim(),input.category(),input.normalBalance(),input.kind(),
                input.parentId()==null?null:get(bankId,input.parentId()).code(),input.active());
    }
    private Map<String,Object> values(Account account) {
        return values(account.code(),account.name(),account.description(),account.category(),account.normalBalance(),account.kind(),account.parentCode(),account.active());
    }
    private Map<String,Object> values(String code,String name,String description,Category category,Balance balance,Kind kind,String parent,boolean active) {
        return Map.of("code",code,"name",name,"description",description,"category",category.name(),"normalBalance",balance.name(),"kind",kind.name(),"parent",parent==null?"":parent,"active",active);
    }
    @Transactional
    public void apply(Actor actor,UUID bankId,Change input,String reason) {
        AccessService.admin(actor);
        Tenant bank=tenants.get(bankId,true);validate(bank,input);
        Map<String,Object> before=before(bankId,input);
        UUID accountId=input.accountId()==null?UUID.randomUUID():input.accountId();
        if(input.accountId()==null)jdbc.update("""
                INSERT INTO platform.chart_account(id,tenant_id,code,name,description,category,normal_balance,kind,parent_id,active)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,accountId,bankId,input.code(),input.name().trim(),input.description().trim(),input.category().name(),input.normalBalance().name(),input.kind().name(),input.parentId(),input.active());
        else jdbc.update("UPDATE platform.chart_account SET name=?,description=?,active=?,updated_at=clock_timestamp() WHERE tenant_id=? AND id=?",
                input.name().trim(),input.description().trim(),input.active(),bankId,accountId);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"CHART_ACCOUNT_CHANGED",bank.version()+1,bank.status(),bank.status(),reason,null,null,
                Map.of("accountId",accountId,"before",before,"after",values(bankId,input)));
    }
    private static Account account(ResultSet r) throws SQLException {
        return new Account(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),Category.valueOf(r.getString("category")),
                Balance.valueOf(r.getString("normal_balance")),Kind.valueOf(r.getString("kind")),r.getObject("parent_id",UUID.class),r.getString("parent_code"),
                r.getBoolean("active"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Chart of accounts change could not be completed");}
}
