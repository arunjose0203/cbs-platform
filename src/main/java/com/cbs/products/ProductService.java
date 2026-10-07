package com.cbs.products;

import static com.cbs.products.ProductModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.ChartService;
import com.cbs.accounting.ChartModels;
import com.cbs.accounting.DayCloseService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ProductService {
    private final JdbcTemplate jdbc; private final TenantRepository tenants;
    private final ChartService chart; private final ReferenceDataService reference;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String SELECT="SELECT p.*,a.code liability_account_code FROM platform.deposit_product p JOIN platform.chart_account a ON a.tenant_id=p.tenant_id AND a.id=p.liability_account_id";
    public ProductService(JdbcTemplate jdbc,TenantRepository tenants,ChartService chart,ReferenceDataService reference){
        this.jdbc=jdbc;this.tenants=tenants;this.chart=chart;this.reference=reference;
    }
    @Transactional
    public Catalogue list(Actor actor,UUID bankId,String search,Type type,Boolean active,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);
        if(search.length()>100)throw error(400,"INVALID_REQUEST");
        jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);
        var bank=tenants.get(bankId,false);String where=" WHERE p.tenant_id=?";
        List<Object> args=new ArrayList<>();args.add(bankId);
        if(!search.isBlank()){where+=" AND position(lower(?) in lower(p.code || ' ' || p.name))>0";args.add(search.trim());}
        if(type!=null){where+=" AND p.product_type=?";args.add(type.name());}
        if(active!=null){where+=" AND p.active=?";args.add(active);}
        long total=jdbc.queryForObject("SELECT count(*) FROM platform.deposit_product p"+where,Long.class,args.toArray());
        args.add(limit);args.add(offset);
        return new Catalogue(bankId,bank.version(),jdbc.query(SELECT+where+" ORDER BY p.code,p.id LIMIT ? OFFSET ?",(r,n)->product(r),args.toArray()),total,limit,offset);
    }
    public Product get(UUID bankId,UUID id){
        var rows=jdbc.query(SELECT+" WHERE p.tenant_id=? AND p.id=?",(r,n)->product(r),bankId,id);
        if(rows.isEmpty())throw error(404,"PRODUCT_NOT_FOUND");return rows.getFirst();
    }
    public Page<Revision> history(Actor actor,UUID bankId,UUID id,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);get(bankId,id);
        return new Page<>(jdbc.query("SELECT * FROM platform.deposit_product_revision WHERE tenant_id=? AND product_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
            (r,n)->new Revision(r.getInt("revision"),JSON.readValue(r.getString("definition"),new TypeReference<Map<String,Object>>(){}),r.getString("approved_by"),r.getString("reason"),r.getTimestamp("created_at").toInstant()),bankId,id,limit,offset),limit,offset);
    }
    // Called under the tenant write lock at both submission and approval.
    public void validate(Tenant bank,Change input){
        DayCloseService.requireOpen(jdbc,bank.id());
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"PRODUCT_BANK_NOT_ACTIVE");
        if(input.productId()!=null){
            var existing=get(bank.id(),input.productId());
            if(!existing.code().equals(input.code())||existing.productType()!=input.productType()||!existing.currency().equals(input.currency())||!existing.liabilityAccountId().equals(input.liabilityAccountId()))throw error(400,"PRODUCT_STRUCTURE_IMMUTABLE");
        }else if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.deposit_product WHERE tenant_id=? AND code=?)",Boolean.class,bank.id(),input.code()))throw error(409,"PRODUCT_CODE_IN_USE");
        // An inactive product may retain a disabled currency or chart mapping, but cannot be activated with it.
        if(input.active()||input.productId()==null){
            if(reference.read(bank).currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(input.currency())))throw error(400,"PRODUCT_CURRENCY_DISABLED");
        }
        var account=chart.get(bank.id(),input.liabilityAccountId());
        if(account.kind()!=ChartModels.Kind.POSTING||account.category()!=ChartModels.Category.LIABILITY||account.normalBalance()!=ChartModels.Balance.CREDIT||((input.active()||input.productId()==null)&&!account.active()))throw error(400,"PRODUCT_INVALID_MAPPING");
        BigDecimal opening=amount(input.minimumOpening(),input.currency()),balance=amount(input.minimumBalance(),input.currency());
        if(opening.compareTo(balance)<0)throw error(400,"PRODUCT_MINIMUM_ORDER");
    }
    private static BigDecimal amount(String value,String currency){
        try{
            if(value==null||!value.matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))throw new IllegalArgumentException();
            var amount=new BigDecimal(value);int scale=Currency.getInstance(currency).getDefaultFractionDigits();
            if(scale<0||amount.stripTrailingZeros().scale()>scale)throw new IllegalArgumentException();
            return amount;
        }catch(IllegalArgumentException e){throw error(400,"PRODUCT_INVALID_AMOUNT");}
    }
    public Map<String,Object> before(UUID bankId,Change input){return input.productId()==null?Map.of():values(get(bankId,input.productId()));}
    public Map<String,Object> values(UUID bankId,Change input){
        return values(input.code(),input.name().trim(),input.description().trim(),input.productType(),input.currency(),amount(input.minimumOpening(),input.currency()).stripTrailingZeros().toPlainString(),amount(input.minimumBalance(),input.currency()).stripTrailingZeros().toPlainString(),input.liabilityAccountId(),chart.get(bankId,input.liabilityAccountId()).code(),input.active());
    }
    private Map<String,Object> values(Product p){return values(p.code(),p.name(),p.description(),p.productType(),p.currency(),p.minimumOpening(),p.minimumBalance(),p.liabilityAccountId(),p.liabilityAccountCode(),p.active());}
    private Map<String,Object> values(String code,String name,String description,Type type,String currency,String opening,String balance,UUID mapping,String mappingCode,boolean active){
        Map<String,Object> result=new LinkedHashMap<>();result.put("code",code);result.put("name",name);result.put("description",description);result.put("productType",type.name());result.put("currency",currency);result.put("minimumOpening",opening);result.put("minimumBalance",balance);result.put("liabilityAccountId",mapping.toString());result.put("liabilityAccountCode",mappingCode);result.put("active",active);return result;
    }
    @Transactional
    public void apply(Actor actor,UUID bankId,Change input,String reason){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);
        var before=before(bankId,input);UUID id=input.productId()==null?UUID.randomUUID():input.productId();
        int revision=input.productId()==null?1:get(bankId,id).revision()+1;
        if(input.productId()==null)jdbc.update("INSERT INTO platform.deposit_product(id,tenant_id,code,name,description,product_type,currency,minimum_opening,minimum_balance,liability_account_id,active,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            id,bankId,input.code(),input.name().trim(),input.description().trim(),input.productType().name(),input.currency(),amount(input.minimumOpening(),input.currency()),amount(input.minimumBalance(),input.currency()),input.liabilityAccountId(),input.active(),revision);
        else jdbc.update("UPDATE platform.deposit_product SET name=?,description=?,minimum_opening=?,minimum_balance=?,active=?,revision=?,updated_at=clock_timestamp() WHERE tenant_id=? AND id=?",
            input.name().trim(),input.description().trim(),amount(input.minimumOpening(),input.currency()),amount(input.minimumBalance(),input.currency()),input.active(),revision,bankId,id);
        var after=values(bankId,input);
        jdbc.update("INSERT INTO platform.deposit_product_revision(tenant_id,product_id,revision,definition,approved_by,reason) VALUES (?,?,?,?::jsonb,?,?)",bankId,id,revision,JSON.writeValueAsString(after),actor.subject(),reason);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"DEPOSIT_PRODUCT_CHANGED",bank.version()+1,bank.status(),bank.status(),reason,null,null,Map.of("productId",id,"revision",revision,"before",before,"after",after));
    }
    private static Product product(ResultSet r)throws SQLException{
        return new Product(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),Type.valueOf(r.getString("product_type")),r.getString("currency"),r.getBigDecimal("minimum_opening").stripTrailingZeros().toPlainString(),r.getBigDecimal("minimum_balance").stripTrailingZeros().toPlainString(),r.getObject("liability_account_id",UUID.class),r.getString("liability_account_code"),r.getBoolean("active"),r.getInt("revision"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Product change could not be completed");}
}
