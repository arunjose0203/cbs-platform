package com.cbs.compliance;

import static com.cbs.compliance.ComplianceModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class ComplianceService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TransactionTemplate tx;
    public ComplianceService(JdbcTemplate jdbc,TenantRepository tenants,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.tenants=tenants;this.tx=new TransactionTemplate(manager);
    }
    private static LocalDate today(){return LocalDate.now(ZoneOffset.UTC);}
    /** Called under the tenant lock, at submission, approval and before first delivery. */
    /** Bulk form of requireClear: customers (by reviewed version) that are currently restricted. */
    public static Set<UUID> restricted(JdbcTemplate jdbc,UUID bank,Map<UUID,Long> versions) {
        if(versions.isEmpty())return Set.of();
        var ids=new ArrayList<UUID>(versions.keySet());var numbers=ids.stream().map(versions::get).toArray(Long[]::new);
        return new HashSet<>(jdbc.query(c->{var s=c.prepareStatement("SELECT DISTINCT c.customer_id FROM platform.compliance_case c JOIN unnest(?::uuid[],?::bigint[]) AS v(id,version) ON v.id=c.customer_id WHERE c.tenant_id=? AND (c.status<>'CLEARED' OR c.reviewed_customer_version<>v.version OR c.review_due<=?)");
            s.setArray(1,c.createArrayOf("uuid",ids.toArray()));s.setArray(2,c.createArrayOf("bigint",numbers));s.setObject(3,bank);s.setObject(4,today());return s;},(r,n)->r.getObject(1,UUID.class)));
    }
    public static void requireClear(JdbcTemplate jdbc,UUID bank,UUID customer,long customerVersion) {
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.compliance_case WHERE tenant_id=? AND customer_id=? AND (status<>'CLEARED' OR reviewed_customer_version<>? OR review_due<=?))",Boolean.class,bank,customer,customerVersion,today()))
            throw error(409,"CUSTOMER_COMPLIANCE_RESTRICTED");
    }
    private Map<String,Object> row(UUID bank,UUID id) {
        var rows=jdbc.queryForList("SELECT c.*,r.customer_number,r.version AS customer_version,r.delivery AS customer_delivery,(c.status<>'CLEARED' OR c.reviewed_customer_version<>r.version OR c.review_due<=?) AS restricted FROM platform.compliance_case c JOIN platform.customer_registry r ON r.tenant_id=c.tenant_id AND r.id=c.customer_id WHERE c.tenant_id=? AND c.id=?",today(),bank,id);
        if(rows.isEmpty())throw error(404,"COMPLIANCE_CASE_NOT_FOUND");return rows.getFirst();
    }
    private Map<String,Object> scoped(Actor actor,UUID bank,UUID id) {
        var value=row(bank,id);if(!actor.issuer().equals(value.get("issuer")))throw error(404,"COMPLIANCE_CASE_NOT_FOUND");return value;
    }
    public Map<String,Object> list(Actor actor,UUID bank,String status,int limit,int offset) {
        AccessService.admin(actor);AccessService.page(limit,offset);
        if(!Set.of("ALL","OPEN","CLEARED","RESTRICTED","REVIEW_DUE").contains(status))throw error(400,"INVALID_REQUEST");
        return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bank);var tenant=tenants.get(bank,false);
            String where=" WHERE c.tenant_id=? AND c.issuer=?";
            var args=new ArrayList<Object>(List.of(bank,actor.issuer()));
            if(status.equals("REVIEW_DUE")){where+=" AND c.status='CLEARED' AND (c.review_due<=? OR c.reviewed_customer_version<>r.version)";args.add(today());}
            else if(!status.equals("ALL")){where+=" AND c.status=?";args.add(status);}
            String from=" FROM platform.compliance_case c JOIN platform.customer_registry r ON r.tenant_id=c.tenant_id AND r.id=c.customer_id";
            long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());
            var selected=new ArrayList<Object>();selected.add(today());selected.addAll(args);selected.add(limit);selected.add(offset);
            var items=jdbc.queryForList("SELECT c.id,c.customer_id,r.customer_number,c.category,c.status,c.version,c.risk,c.review_due,c.updated_at,(c.status<>'CLEARED' OR c.reviewed_customer_version<>r.version OR c.review_due<=?) AS restricted"+from+where+" ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",selected.toArray());
            return Map.of("version",tenant.version(),"items",items,"total",total,"limit",limit,"offset",offset,"today",today());
        });
    }
    public Map<String,Object> detail(Actor actor,UUID bank,UUID id) {
        AccessService.admin(actor);return tx.execute(t->{
            var tenant=tenants.get(bank,true);var result=detailValues(actor,tenant,id);
            tenants.audit(actor,bank,"COMPLIANCE_CASE_VIEWED",tenant.version(),tenant.status(),tenant.status(),"Compliance evidence viewed",null,null,Map.of("caseId",id));return result;
        });
    }
    private Map<String,Object> detailValues(Actor actor,Tenant bank,UUID id) {
        var events=jdbc.query("SELECT action,subject,created_at,payload FROM platform.compliance_event WHERE tenant_id=? AND case_id=? ORDER BY version DESC LIMIT 100",(r,n)->Map.of("action",r.getString(1),"subject",r.getString(2),"at",r.getTimestamp(3).toInstant(),"evidence",JSON.readValue(r.getString(4),new TypeReference<Map<String,Object>>(){})),bank.id(),id);
        return Map.of("version",bank.version(),"case",scoped(actor,bank.id(),id),"events",events);
    }
    private void active(Tenant bank,long expected) {
        if(bank.version()!=expected)throw error(409,"STALE_VERSION");
        if(bank.status()!=Status.ACTIVE)throw error(409,"CUSTOMER_BANK_NOT_ACTIVE");
    }
    private UUID replay(Actor actor,UUID bank,UUID key,Object input,UUID caseId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},actor.issuer()+":"+actor.subject()+":"+key);
        var saved=jdbc.queryForList("SELECT tenant_id,case_id,payload=?::jsonb AS matches FROM platform.compliance_command WHERE issuer=? AND subject=? AND idempotency_key=?",JSON.writeValueAsString(input),actor.issuer(),actor.subject(),key);
        if(saved.isEmpty())return null;var r=saved.getFirst();
        if(!bank.equals(r.get("tenant_id"))||!Boolean.TRUE.equals(r.get("matches"))||(caseId!=null&&!caseId.equals(r.get("case_id"))))throw error(409,"IDEMPOTENCY_CONFLICT");
        return (UUID)r.get("case_id");
    }
    private void command(Actor actor,UUID bank,UUID id,UUID key,Object input) {
        jdbc.update("INSERT INTO platform.compliance_command VALUES (?,?,?,?,?,?::jsonb)",actor.issuer(),actor.subject(),key,bank,id,JSON.writeValueAsString(input));
    }
    public Map<String,Object> open(Actor actor,UUID bankId,UUID key,Open input) {
        AccessService.admin(actor);return tx.execute(t->{
            UUID saved=replay(actor,bankId,key,input,null);var bank=tenants.get(bankId,true);if(saved!=null)return detailValues(actor,bank,saved);
            active(bank,input.expectedVersion());
            if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=?)",Boolean.class,bankId,input.customerId()))throw error(404,"CUSTOMER_NOT_FOUND");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO platform.compliance_case(id,tenant_id,customer_id,category,issuer,opened_by) VALUES (?,?,?,?,?,?)",id,bankId,input.customerId(),input.category().name(),actor.issuer(),actor.subject());
            event(actor,bankId,id,"OPENED",null,Map.of("evidenceReference",input.evidenceReference(),"reason",input.reason()));
            command(actor,bankId,id,key,input);changed(actor,bank,"COMPLIANCE_CASE_OPENED",id,input.reason());return detailValues(actor,tenants.get(bankId,false),id);
        });
    }
    public Map<String,Object> note(Actor actor,UUID bankId,UUID id,UUID key,Note input) {
        AccessService.admin(actor);return tx.execute(t->{
            UUID saved=replay(actor,bankId,key,input,id);var bank=tenants.get(bankId,true);if(saved!=null)return detailValues(actor,bank,saved);
            active(bank,input.expectedVersion());var old=scoped(actor,bankId,id);if(((Number)old.get("version")).longValue()!=input.caseVersion())throw error(409,"COMPLIANCE_CASE_STALE");
            jdbc.update("UPDATE platform.compliance_case SET version=version+1,updated_at=clock_timestamp() WHERE id=?",id);
            event(actor,bankId,id,"NOTE",null,Map.of("evidenceReference",input.evidenceReference(),"reason",input.reason()));
            command(actor,bankId,id,key,input);changed(actor,bank,"COMPLIANCE_EVIDENCE_ADDED",id,input.reason());return detailValues(actor,tenants.get(bankId,false),id);
        });
    }
    public void validate(Tenant bank,Review input) {
        active(bank,input.expectedVersion());var old=row(bank.id(),input.caseId());
        if(((Number)old.get("version")).longValue()!=input.caseVersion()||((Number)old.get("customer_version")).longValue()!=input.customerVersion())throw error(409,"COMPLIANCE_CASE_STALE");
        if(input.decision()==Decision.CLEARED&&(!"APPLIED".equals(old.get("customer_delivery"))||input.reviewDue()==null||!input.reviewDue().isAfter(today())||input.reviewDue().getYear()>2200))throw error(409,"COMPLIANCE_REVIEW_INVALID");
        if(input.decision()==Decision.RESTRICTED&&input.reviewDue()!=null)throw error(400,"COMPLIANCE_REVIEW_INVALID");
    }
    public Map<String,Object> before(Actor actor,UUID bank,Review input){return scoped(actor,bank,input.caseId());}
    public Map<String,Object> values(Review input){var values=new LinkedHashMap<String,Object>();values.put("caseId",input.caseId());values.put("decision",input.decision());values.put("risk",input.risk());values.put("customerVersion",input.customerVersion());values.put("identityEvidence",input.identityEvidence());values.put("screeningEvidence",input.screeningEvidence());values.put("reviewDue",input.reviewDue());values.put("reason",input.reason());return values;}
    public void apply(Actor actor,UUID bankId,Review input,UUID approvalId) {
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);var old=scoped(actor,bankId,input.caseId());
        if(actor.subject().equals(old.get("opened_by")))throw error(403,"COMPLIANCE_INDEPENDENT_REVIEW_REQUIRED");
        jdbc.update("UPDATE platform.compliance_case SET status=?,risk=?,reviewed_customer_version=?,review_due=?,version=version+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=?",input.decision().name(),input.risk().name(),input.customerVersion(),input.reviewDue(),bankId,input.caseId());
        event(actor,bankId,input.caseId(),"REVIEWED",approvalId,values(input));changed(actor,bank,"COMPLIANCE_REVIEW_APPROVED",input.caseId(),input.reason());
    }
    private void event(Actor actor,UUID bank,UUID id,String action,UUID approval,Map<String,Object> evidence) {
        var state=row(bank,id);var payload=new LinkedHashMap<String,Object>(evidence);
        payload.put("status",state.get("status"));payload.put("risk",state.get("risk"));payload.put("reviewedCustomerVersion",state.get("reviewed_customer_version"));payload.put("reviewDue",state.get("review_due")==null?null:state.get("review_due").toString());
        jdbc.update("INSERT INTO platform.compliance_event(id,tenant_id,case_id,version,action,issuer,subject,correlation_id,approval_id,payload) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),bank,id,state.get("version"),action,actor.issuer(),actor.subject(),actor.correlationId(),approval,JSON.writeValueAsString(payload));
    }
    private void changed(Actor actor,Tenant bank,String action,UUID id,String reason) {
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),reason,null,null,Map.of("caseId",id));
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Compliance review requires attention");}
}
