package com.cbs.customers;

import static com.cbs.customers.CustomerModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CustomerService {
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final BankCustomerStore store;private final BankDatabaseProvisioner databases;private final TransactionTemplate tx;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> COUNTRIES=Set.of(Locale.getISOCountries());
    public CustomerService(JdbcTemplate jdbc,TenantRepository tenants,BankCustomerStore store,BankDatabaseProvisioner databases,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.store=store;this.databases=databases;this.tx=new TransactionTemplate(manager);}
    public static String documentKey(Profile p){return p.documentCountry()+":"+p.documentType()+":"+p.documentNumber().replaceAll("[ -]","").toUpperCase(Locale.ROOT);}
    private static String nameKey(String s){return s.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    private boolean ready(Tenant bank){return List.of(Status.ACTIVE,Status.SUSPENDED).contains(bank.status())&&store.ready(bank);}
    public Directory list(Actor actor,UUID bankId,String search,State state,int limit,int offset){
        AccessService.admin(actor);AccessService.page(limit,offset);if(search.length()>100)throw error(400,"INVALID_REQUEST");
        return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);
            String where=" WHERE r.tenant_id=?";List<Object> args=new ArrayList<>();args.add(bankId);
            if(!search.isBlank()){where+=" AND position(lower(?) in lower(r.customer_number || ' ' || (d.payload->'profile'->>'fullName')))>0";args.add(search.trim());}
            if(state!=null){where+=" AND d.payload->'profile'->>'state'=?";args.add(state.name());}
            String from=" FROM platform.customer_registry r JOIN platform.customer_delivery d ON d.request_id=r.request_id";
            long total=jdbc.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());args.add(limit);args.add(offset);
            var rows=jdbc.query("SELECT r.*,d.payload,d.failure_code"+from+where+" ORDER BY r.customer_number LIMIT ? OFFSET ?",(r,n)->{
                var d=JSON.readValue(r.getString("payload"),Definition.class);return new Summary(d.id(),d.customerNumber(),d.profile().fullName(),d.profile().state(),d.version(),r.getString("delivery"),r.getObject("request_id",UUID.class),r.getString("failure_code"),d.profile().kind());
            },args.toArray());
            return new Directory(bankId,bank.version(),ready(bank),rows,total,limit,offset);
        });
    }
    private Detail current(UUID bankId,UUID id){
        var rows=jdbc.query("SELECT d.payload,r.delivery,r.request_id,d.failure_code FROM platform.customer_registry r JOIN platform.customer_delivery d ON d.request_id=r.request_id WHERE r.tenant_id=? AND r.id=?",(r,n)->new Detail(JSON.readValue(r.getString("payload"),Definition.class),r.getString("delivery"),r.getObject("request_id",UUID.class),r.getString("failure_code")),bankId,id);
        if(rows.isEmpty())throw error(404,"CUSTOMER_NOT_FOUND");return rows.getFirst();
    }
    public Detail detail(Actor actor,UUID bankId,UUID id){
        AccessService.admin(actor);return tx.execute(t->{
            jdbc.query("SELECT id FROM platform.tenant WHERE id=? FOR SHARE",r->{},bankId);var bank=tenants.get(bankId,false);var row=current(bankId,id);
            if(row.delivery().equals("APPLIED")&&!row.definition().equals(store.read(bank,id)))throw error(503,"CUSTOMER_RECONCILIATION_REQUIRED");
            tenants.audit(actor,bankId,"CUSTOMER_PROFILE_VIEWED",bank.version(),bank.status(),bank.status(),"Customer detail viewed",null,null,Map.of("customerId",id));return row;
        });
    }
    // Internal account-opening checks; caller holds the tenant lock.
    public String number(UUID bankId,UUID id){return current(bankId,id).definition().customerNumber();}
    /**
     * Bulk eligibility for batch processing, mirroring requireEligible: returns the failure code
     * of each ineligible customer. A bank/platform mismatch is a reconciliation error, not ineligibility.
     */
    public Map<UUID,String> ineligible(Tenant bank,Collection<UUID> ids){
        var result=new HashMap<UUID,String>();if(ids.isEmpty())return result;var unique=new ArrayList<>(new LinkedHashSet<>(ids));
        var rows=new HashMap<UUID,Detail>();
        jdbc.query(c->{var s=c.prepareStatement("SELECT r.id,d.payload,r.delivery,r.request_id,d.failure_code FROM platform.customer_registry r JOIN platform.customer_delivery d ON d.request_id=r.request_id WHERE r.tenant_id=? AND r.id=ANY(?)");s.setObject(1,bank.id());s.setArray(2,c.createArrayOf("uuid",unique.toArray()));return s;},
            r->{rows.put(r.getObject(1,UUID.class),new Detail(JSON.readValue(r.getString(2),Definition.class),r.getString(3),r.getObject(4,UUID.class),r.getString(5)));});
        var stored=store.readAll(bank,unique);var versions=new HashMap<UUID,Long>();
        for(UUID id:unique){
            var row=rows.get(id);if(row==null)throw error(404,"CUSTOMER_NOT_FOUND");
            if(!row.delivery().equals("APPLIED")){result.put(id,"ACCOUNT_CUSTOMER_NOT_READY");continue;}
            if(!row.definition().equals(stored.get(id)))throw error(503,"CUSTOMER_RECONCILIATION_REQUIRED");
            try{checkEligible(row.definition());}catch(TenantException e){result.put(id,e.code());continue;}
            versions.put(id,row.definition().version());
        }
        for(UUID id:com.cbs.compliance.ComplianceService.restricted(jdbc,bank.id(),versions))result.put(id,"CUSTOMER_COMPLIANCE_RESTRICTED");
        // Organizations also need a recorded director and eligible related persons.
        for(UUID id:unique){
            if(result.containsKey(id)||rows.get(id).definition().profile().kind()!=CustomerType.ORGANIZATION)continue;
            var problem=organizationProblem(bank.id(),id);if(problem!=null){result.put(id,problem);continue;}
            var persons=parties(bank.id(),id);if(!persons.isEmpty()&&!ineligible(bank,persons).isEmpty())result.put(id,"ORGANIZATION_PARTY_RESTRICTED");
        }
        return result;
    }
    public void requireEligible(Tenant bank,UUID id){
        var row=current(bank.id(),id);
        if(!row.delivery().equals("APPLIED"))throw error(409,"ACCOUNT_CUSTOMER_NOT_READY");
        if(!row.definition().equals(store.read(bank,id)))throw error(503,"CUSTOMER_RECONCILIATION_REQUIRED");
        checkEligible(row.definition());
        com.cbs.compliance.ComplianceService.requireClear(jdbc,bank.id(),id,row.definition().version());
        if(row.definition().profile().kind()==CustomerType.ORGANIZATION){
            var problem=organizationProblem(bank.id(),id);if(problem!=null)throw error(409,problem);
            var persons=parties(bank.id(),id);if(!persons.isEmpty()&&!ineligible(bank,persons).isEmpty())throw error(409,"ORGANIZATION_PARTY_RESTRICTED");
        }
    }
    /** Persons recorded against an organization (directors, beneficial owners, representatives). */
    private List<UUID> parties(UUID bankId,UUID organization){return jdbc.queryForList("SELECT DISTINCT person_id FROM platform.customer_party WHERE tenant_id=? AND organization_id=?",UUID.class,bankId,organization);}
    /** An organization can transact only once at least one director is recorded. */
    private String organizationProblem(UUID bankId,UUID organization){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_party WHERE tenant_id=? AND organization_id=? AND role='DIRECTOR')",Boolean.class,bankId,organization)?null:"ORGANIZATION_PARTIES_REQUIRED";}
    public static void checkEligible(Definition customer){
        var p=customer.profile();
        if(p.state()!=State.ACTIVE||p.reviewReference().isBlank()||(p.documentExpiry()!=null&&p.documentExpiry().isBefore(LocalDate.now(java.time.ZoneOffset.UTC))))throw error(409,"ACCOUNT_CUSTOMER_NOT_READY");
    }
    public void initialize(Actor actor,UUID bankId,ChangeRequest input){
        AccessService.admin(actor);tx.executeWithoutResult(t->{var bank=tenants.get(bankId,true);
            if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error(409,"CUSTOMER_BANK_NOT_ACTIVE");
            String version=databases.upgradeOwned(bank.id(),bank.databaseBinding().databaseReference());
            jdbc.update("UPDATE platform.database_binding SET schema_version=? WHERE tenant_id=?",version,bankId);tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
            tenants.audit(actor,bankId,"CUSTOMER_STORAGE_PREPARED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("schemaVersion",version));
        });
    }
    // Both submission and approval hold the tenant write lock.
    public void validate(Tenant bank,Change input){
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error(409,"CUSTOMER_BANK_NOT_ACTIVE");
        if(!store.ready(bank))throw error(409,"CUSTOMER_STORAGE_NOT_READY");
        var p=input.profile();LocalDate today=LocalDate.now(java.time.ZoneOffset.UTC);
        if(p.birthDate().isAfter(today)||p.birthDate().isBefore(LocalDate.of(1900,1,1))||!COUNTRIES.contains(p.nationality())||!COUNTRIES.contains(p.residenceCountry())||!COUNTRIES.contains(p.documentCountry()))throw error(400,"CUSTOMER_INVALID_PROFILE");
        if(p.documentNumber().replaceAll("[ -]","").length()<3)throw error(400,"CUSTOMER_INVALID_PROFILE");
        // Organizations identify with a registration certificate; people with an identity document.
        if((p.kind()==CustomerType.ORGANIZATION)!=(p.documentType()==DocumentType.REGISTRATION_CERTIFICATE))throw error(400,"CUSTOMER_TYPE_DOCUMENT_MISMATCH");
        if(p.documentExpiry()!=null&&(p.documentExpiry().isBefore(p.birthDate())||p.documentExpiry().getYear()>2200))throw error(400,"CUSTOMER_INVALID_PROFILE");
        if(p.state()==State.ACTIVE&&(p.reviewReference().isBlank()||(p.documentExpiry()!=null&&p.documentExpiry().isBefore(today))))throw error(400,"CUSTOMER_REVIEW_REQUIRED");
        UUID exclude=input.customerId()==null?new UUID(0,0):input.customerId();
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND document_key=? AND id<>?)",Boolean.class,bank.id(),documentKey(p),exclude))throw error(409,"CUSTOMER_DOCUMENT_DUPLICATE");
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND name_key=? AND birth_date=? AND id<>?)",Boolean.class,bank.id(),nameKey(p.fullName()),p.birthDate(),exclude)&&input.duplicateReview().isBlank())throw error(409,"CUSTOMER_DUPLICATE_REVIEW_REQUIRED");
        if(input.customerId()!=null){var row=current(bank.id(),input.customerId());if(!row.delivery().equals("APPLIED"))throw error(409,"CUSTOMER_DELIVERY_PENDING");
            if(row.definition().profile().kind()!=p.kind())throw error(409,"CUSTOMER_TYPE_IMMUTABLE");
            if(!row.definition().equals(store.read(bank,input.customerId())))throw error(503,"CUSTOMER_RECONCILIATION_REQUIRED");}
    }
    public Map<String,Object> before(UUID bankId,Change input){return input.customerId()==null?Map.of():values(current(bankId,input.customerId()).definition().profile(),"");}
    public Map<String,Object> values(Change input){return values(input.profile(),input.duplicateReview());}
    private Map<String,Object> values(Profile p,String duplicateReview){
        Map<String,Object> m=new LinkedHashMap<>();m.put("fullName",p.fullName().trim());m.put("birthDate",p.birthDate().toString());m.put("nationality",p.nationality());m.put("residenceCountry",p.residenceCountry());m.put("address",p.address().trim());m.put("phone",p.phone());m.put("email",p.email());m.put("documentType",p.documentType().name());m.put("documentCountry",p.documentCountry());String number=p.documentNumber().replaceAll("[ -]","");m.put("documentNumber","••••"+number.substring(Math.max(0,number.length()-2)));m.put("documentExpiry",p.documentExpiry()==null?"":p.documentExpiry().toString());m.put("documentReference",p.documentReference());m.put("state",p.state().name());m.put("reviewReference",p.reviewReference());m.put("duplicateReview",duplicateReview);return m;
    }
    private com.cbs.compliance.ScreeningService screening;
    @org.springframework.beans.factory.annotation.Autowired(required=false) void screening(com.cbs.compliance.ScreeningService screening){this.screening=screening;}
    public void enqueue(Actor reviewer,UUID bankId,Change input,UUID requestId,String maker){
        AccessService.admin(reviewer);var bank=tenants.get(bankId,true);validate(bank,input);UUID id=input.customerId()==null?UUID.randomUUID():input.customerId();
        var old=input.customerId()==null?null:current(bankId,id).definition();String number=old==null?"C"+id.toString().replace("-","").toUpperCase(Locale.ROOT):old.customerNumber();
        long version=old==null?0:old.version()+1;var d=new Definition(id,bankId,number,version,input.profile());var p=input.profile();
        if(old==null)jdbc.update("INSERT INTO platform.customer_registry(id,tenant_id,customer_number,document_key,name_key,birth_date,version,request_id,delivery,customer_type) VALUES (?,?,?,?,?,?,?,?,'PENDING',?)",id,bankId,number,documentKey(p),nameKey(p.fullName()),p.birthDate(),version,requestId,p.kind().name());
        else jdbc.update("UPDATE platform.customer_registry SET document_key=?,name_key=?,birth_date=?,version=?,request_id=?,delivery='PENDING' WHERE tenant_id=? AND id=?",documentKey(p),nameKey(p.fullName()),p.birthDate(),version,requestId,bankId,id);
        jdbc.update("INSERT INTO platform.customer_delivery(request_id,tenant_id,customer_id,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES (?,?,?,?::jsonb,?,?,?,?)",requestId,bankId,id,JSON.writeValueAsString(d),maker,reviewer.issuer(),reviewer.subject(),reviewer.correlationId());
        // Every approved profile version is screened against the bank's active lists before it is used.
        if(screening!=null)screening.screenCustomer(bankId,id,version,p.fullName(),p.birthDate(),p.kind()==CustomerModels.CustomerType.INDIVIDUAL);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(reviewer,bankId,"CUSTOMER_CHANGE_APPROVED",bank.version()+1,bank.status(),bank.status(),"Approved request "+requestId,null,null,Map.of("customerId",id,"requestId",requestId,"delivery","PENDING"));
    }
    public List<UUID> pending(){return jdbc.query("SELECT request_id FROM platform.customer_delivery WHERE NOT completed ORDER BY last_attempt_at NULLS FIRST,created_at LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    public void retry(Actor actor,UUID bankId,UUID id){AccessService.admin(actor);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_delivery WHERE request_id=? AND tenant_id=? AND reviewer_issuer=?)",Boolean.class,id,bankId,actor.issuer()))throw error(404,"APPROVAL_NOT_FOUND");deliver(id);}
    public void deliver(UUID id){tx.executeWithoutResult(t->{
        var jobs=jdbc.query("SELECT * FROM platform.customer_delivery WHERE request_id=? AND NOT completed FOR UPDATE",(r,n)->new Dispatch(id,JSON.readValue(r.getString("payload"),Definition.class),r.getString("maker_subject"),r.getString("reviewer_issuer"),r.getString("reviewer_subject"),r.getObject("correlation_id",UUID.class)),id);
        if(jobs.isEmpty())return;var job=jobs.getFirst();var bank=tenants.get(job.definition().bankId(),true);String failure=null;
        if(bank.status()!=Status.ACTIVE)failure="CUSTOMER_BANK_NOT_ACTIVE";else try{store.deliver(bank,job);}catch(TenantException e){failure=e.code();}
        jdbc.update("UPDATE platform.customer_delivery SET attempts=attempts+1,last_attempt_at=clock_timestamp(),failure_code=? WHERE request_id=?",failure,id);if(failure!=null)return;
        jdbc.update("UPDATE platform.customer_delivery SET completed=true,completed_at=clock_timestamp() WHERE request_id=?",id);jdbc.update("UPDATE platform.customer_registry SET delivery='APPLIED' WHERE id=? AND request_id=?",job.definition().id(),id);
        tenants.audit(new Actor(job.reviewerIssuer(),job.reviewerSubject(),true,job.correlationId()),bank.id(),"CUSTOMER_CHANGE_DELIVERED",bank.version(),bank.status(),bank.status(),"Delivered approved request "+id,null,null,Map.of("customerId",job.definition().id(),"requestId",id));
    });}
    private static TenantException error(int status,String code){return new TenantException(status,code,"Customer onboarding operation could not be completed");}
}
