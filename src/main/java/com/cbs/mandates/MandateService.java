package com.cbs.mandates;

import static com.cbs.mandates.MandateModels.*;
import com.cbs.access.AccessService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Organization related persons and account mandates. Both are reference data in the control
 * database, changed only through independent approval; debits check them at submission and approval.
 */
@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class MandateService {
    private final JdbcTemplate jdbc;private final TenantRepository tenants;private final com.cbs.customers.CustomerService customers;private final com.cbs.reference.ReferenceDataService reference;
    public MandateService(JdbcTemplate jdbc,TenantRepository tenants,com.cbs.customers.CustomerService customers,com.cbs.reference.ReferenceDataService reference){this.jdbc=jdbc;this.tenants=tenants;this.customers=customers;this.reference=reference;}

    private record Person(UUID id,String number,String type,LocalDate born,String delivery){
        boolean adultOn(LocalDate d){return type.equals("INDIVIDUAL")&&!born.plusYears(com.cbs.customers.CustomerModels.MAJORITY_AGE).isAfter(d);}
    }
    private Map<UUID,Person> people(UUID bankId,Collection<UUID> ids){
        var result=new HashMap<UUID,Person>();if(ids.isEmpty())return result;
        jdbc.query(c->{var s=c.prepareStatement("SELECT id,customer_number,customer_type,birth_date,delivery FROM platform.customer_registry WHERE tenant_id=? AND id=ANY(?)");s.setObject(1,bankId);s.setArray(2,c.createArrayOf("uuid",ids.toArray()));return s;},
            r->{result.put(r.getObject(1,UUID.class),new Person(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getObject(4,LocalDate.class),r.getString(5)));});
        return result;
    }
    private LocalDate today(Tenant bank){return reference.read(bank).businessDate();}

    // ---- Organization parties ----
    public void validate(Tenant bank,Parties input){
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=TenantModels.Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        var org=people(bank.id(),List.of(input.organizationId())).get(input.organizationId());
        if(org==null||!org.delivery().equals("APPLIED"))throw error(404,"CUSTOMER_NOT_FOUND");if(!org.type().equals("ORGANIZATION"))throw error(409,"CUSTOMER_NOT_ORGANIZATION");
        var persons=people(bank.id(),input.parties().stream().map(Party::personId).toList());var seen=new HashSet<String>();BigDecimal owned=BigDecimal.ZERO;LocalDate day=today(bank);
        for(var p:input.parties()){
            var person=persons.get(p.personId());
            if(person==null||!person.delivery().equals("APPLIED"))throw error(404,"CUSTOMER_NOT_FOUND");
            if(!person.adultOn(day))throw error(409,"PARTY_MUST_BE_ADULT_INDIVIDUAL");
            if(!seen.add(p.personId()+":"+p.role()))throw error(400,"PARTY_DUPLICATE");
            if((p.role()==PartyRole.BENEFICIAL_OWNER)!=(p.ownershipPercent()!=null))throw error(400,"PARTY_OWNERSHIP_INVALID");
            if(p.ownershipPercent()!=null){var pct=new BigDecimal(p.ownershipPercent());if(pct.signum()<=0||pct.compareTo(BigDecimal.valueOf(100))>0)throw error(400,"PARTY_OWNERSHIP_INVALID");owned=owned.add(pct);}
        }
        if(owned.compareTo(BigDecimal.valueOf(100))>0)throw error(400,"PARTY_OWNERSHIP_INVALID");
        if(input.parties().stream().noneMatch(p->p.role()==PartyRole.DIRECTOR))throw error(400,"ORGANIZATION_PARTIES_REQUIRED");
    }
    public Map<String,Object> values(Tenant bank,Parties input){
        var persons=people(bank.id(),input.parties().stream().map(Party::personId).toList());
        return Map.of("organization",people(bank.id(),List.of(input.organizationId())).get(input.organizationId()).number(),
            "parties",input.parties().stream().map(p->persons.get(p.personId()).number()+" · "+p.role()+(p.ownershipPercent()==null?"":" · "+p.ownershipPercent()+"%")).sorted().toList());
    }
    public Map<String,Object> partiesBefore(UUID bankId,UUID organization){
        var rows=parties(bankId,organization);return rows.isEmpty()?Map.of():Map.of("parties",rows.stream().map(p->p.customerNumber()+" · "+p.role()+(p.ownershipPercent()==null?"":" · "+p.ownershipPercent()+"%")).sorted().toList());
    }
    public void apply(Actor actor,UUID bankId,Parties input,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);
        jdbc.update("DELETE FROM platform.customer_party WHERE tenant_id=? AND organization_id=?",bankId,input.organizationId());
        for(var p:input.parties())jdbc.update("INSERT INTO platform.customer_party(tenant_id,organization_id,person_id,role,ownership_percent,approval_id) VALUES (?,?,?,?,?,?)",bankId,input.organizationId(),p.personId(),p.role().name(),p.ownershipPercent()==null?null:new BigDecimal(p.ownershipPercent()),approvalId);
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"CUSTOMER_PARTIES_CHANGED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("organizationId",input.organizationId(),"parties",input.parties().size()));
    }
    public List<PartyView> parties(Actor actor,UUID bankId,UUID organization){AccessService.admin(actor);tenants.get(bankId,false);return parties(bankId,organization);}
    private List<PartyView> parties(UUID bankId,UUID organization){
        return jdbc.query("SELECT p.person_id,r.customer_number,p.role,p.ownership_percent FROM platform.customer_party p JOIN platform.customer_registry r ON r.id=p.person_id WHERE p.tenant_id=? AND p.organization_id=? ORDER BY p.role,r.customer_number",
            (r,n)->new PartyView(r.getObject(1,UUID.class),r.getString(2),PartyRole.valueOf(r.getString(3)),r.getBigDecimal(4)==null?null:r.getBigDecimal(4).stripTrailingZeros().toPlainString()),bankId,organization);
    }

    // ---- Account mandates ----
    private record Account(UUID id,UUID customer,String state,String delivery){}
    private Account account(UUID bankId,UUID id){
        var rows=jdbc.query("SELECT id,customer_id,state,delivery FROM platform.account_registry WHERE tenant_id=? AND id=?",(r,n)->new Account(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4)),bankId,id);
        if(rows.isEmpty())throw error(404,"ACCOUNT_NOT_FOUND");return rows.getFirst();
    }
    public void validate(Tenant bank,Mandate input){
        if(bank.version()!=input.expectedVersion())throw error(409,"STALE_VERSION");if(bank.status()!=TenantModels.Status.ACTIVE)throw error(409,"ACCOUNT_BANK_NOT_ACTIVE");
        var a=account(bank.id(),input.accountId());if(!a.delivery().equals("APPLIED")||a.state().equals("CLOSED"))throw error(409,"FUNDING_ACCOUNT_NOT_READY");
        com.cbs.accounts.AccountClosureService.requireNotSettling(jdbc,a.id());
        var ids=new ArrayList<UUID>(input.parties().stream().map(AccountParty::customerId).toList());ids.add(a.customer());var persons=people(bank.id(),ids);var primary=persons.get(a.customer());LocalDate day=today(bank);
        var seen=new HashSet<UUID>();
        for(var p:input.parties()){
            var person=persons.get(p.customerId());
            if(person==null||!person.delivery().equals("APPLIED"))throw error(404,"CUSTOMER_NOT_FOUND");
            if(p.customerId().equals(a.customer())||!seen.add(p.customerId()))throw error(400,"PARTY_DUPLICATE");
            if(!person.adultOn(day))throw error(409,"PARTY_MUST_BE_ADULT_INDIVIDUAL");
        }
        boolean organization=primary.type().equals("ORGANIZATION"),minor=!organization&&!primary.adultOn(day);
        var roles=input.parties().stream().map(AccountParty::role).toList();
        if(organization){
            if(roles.stream().anyMatch(r->r!=AccountRole.SIGNATORY))throw error(400,"MANDATE_ROLE_INVALID");
            // Organization signatories must be its recorded directors or representatives.
            var authorised=new HashSet<>(jdbc.queryForList("SELECT person_id FROM platform.customer_party WHERE tenant_id=? AND organization_id=? AND role IN ('DIRECTOR','REPRESENTATIVE')",UUID.class,bank.id(),a.customer()));
            if(input.parties().stream().anyMatch(p->!authorised.contains(p.customerId())))throw error(409,"MANDATE_SIGNATORY_NOT_AUTHORIZED");
        }else if(minor){
            if(roles.stream().anyMatch(r->r!=AccountRole.GUARDIAN))throw error(400,"MANDATE_ROLE_INVALID");
        }else if(roles.contains(AccountRole.GUARDIAN))throw error(400,"MANDATE_ROLE_INVALID");
        int signers=signerIds(organization,minor,a.customer(),input.parties()).size();
        if(signers==0)throw error(400,organization?"MANDATE_SIGNATORY_REQUIRED":"MANDATE_GUARDIAN_REQUIRED");
        if(input.rule()==SigningRule.ANY_TWO&&signers<2)throw error(400,"MANDATE_RULE_UNSATISFIABLE");
    }
    private static List<UUID> signerIds(boolean organization,boolean minor,UUID primary,List<AccountParty> parties){
        var list=new ArrayList<UUID>();if(!organization&&!minor)list.add(primary);
        for(var p:parties)if(p.role()!=AccountRole.JOINT_HOLDER||(!organization&&!minor))list.add(p.customerId());
        return list;
    }
    public Map<String,Object> values(Tenant bank,Mandate input){
        var a=account(bank.id(),input.accountId());var persons=people(bank.id(),input.parties().stream().map(AccountParty::customerId).toList());
        return Map.of("accountNumber",jdbc.queryForObject("SELECT account_number FROM platform.account_registry WHERE id=?",String.class,a.id()),"rule",input.rule().name(),
            "parties",input.parties().stream().map(p->persons.get(p.customerId()).number()+" · "+p.role()).sorted().toList());
    }
    public Map<String,Object> mandateBefore(UUID bankId,UUID accountId){
        var rules=jdbc.queryForList("SELECT signing_rule FROM platform.account_mandate WHERE account_id=? AND tenant_id=?",String.class,accountId,bankId);if(rules.isEmpty())return Map.of();
        return Map.of("rule",rules.getFirst(),"parties",jdbc.queryForList("SELECT r.customer_number||' · '||p.role FROM platform.account_party p JOIN platform.customer_registry r ON r.id=p.customer_id WHERE p.account_id=? ORDER BY 1",String.class,accountId));
    }
    public void apply(Actor actor,UUID bankId,Mandate input,UUID approvalId){
        AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);
        jdbc.update("INSERT INTO platform.account_mandate(account_id,tenant_id,signing_rule,approval_id) VALUES (?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET signing_rule=excluded.signing_rule,approval_id=excluded.approval_id,updated_at=clock_timestamp()",input.accountId(),bankId,input.rule().name(),approvalId);
        jdbc.update("DELETE FROM platform.account_party WHERE account_id=?",input.accountId());
        for(var p:input.parties())jdbc.update("INSERT INTO platform.account_party(account_id,customer_id,role) VALUES (?,?,?)",input.accountId(),p.customerId(),p.role().name());
        tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
        tenants.audit(actor,bankId,"ACCOUNT_MANDATE_CHANGED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,Map.of("accountId",input.accountId(),"rule",input.rule().name(),"parties",input.parties().size()));
    }
    public View view(Actor actor,UUID bankId,UUID accountId){AccessService.admin(actor);var bank=tenants.get(bankId,false);return view(bank,accountId);}
    public View view(Tenant bank,UUID accountId){
        var a=account(bank.id(),accountId);var rules=jdbc.queryForList("SELECT signing_rule FROM platform.account_mandate WHERE account_id=? AND tenant_id=?",String.class,accountId,bank.id());
        var parties=jdbc.query("SELECT customer_id,role FROM platform.account_party WHERE account_id=?",(r,n)->new AccountParty(r.getObject(1,UUID.class),AccountRole.valueOf(r.getString(2))),accountId);
        var ids=new ArrayList<UUID>(parties.stream().map(AccountParty::customerId).toList());ids.add(a.customer());var persons=people(bank.id(),ids);var primary=persons.get(a.customer());
        boolean organization=primary.type().equals("ORGANIZATION"),minor=!organization&&!primary.adultOn(today(bank)),configured=!rules.isEmpty();
        var rule=configured?SigningRule.valueOf(rules.getFirst()):SigningRule.ANY_ONE;var signerIds=signerIds(organization,minor,a.customer(),parties);
        var roleOf=new HashMap<UUID,String>();roleOf.put(a.customer(),"HOLDER");for(var p:parties)roleOf.put(p.customerId(),p.role().name());
        var signers=signerIds.stream().map(id->new Signer(id,persons.get(id).number(),roleOf.get(id))).toList();
        int needed=switch(rule){case ANY_ONE->1;case ANY_TWO->2;case ALL->signers.size();};
        return new View(accountId,a.customer(),primary.type(),minor,configured,configured||organization||minor,rule,needed,signers,parties.stream().map(p->new Signer(p.customerId(),persons.get(p.customerId()).number(),p.role().name())).toList());
    }
    /**
     * Checks that a customer debit carries enough distinct, eligible authorised signers.
     * An organization or a minor's account without an approved mandate cannot be debited.
     */
    public void requireAuthorized(Tenant bank,UUID accountId,List<UUID> provided){
        var v=view(bank,accountId);var given=provided==null?List.<UUID>of():provided;
        if(!v.required()){
            if(given.stream().anyMatch(id->!id.equals(v.primaryCustomerId())))throw error(409,"MANDATE_SIGNATORY_INVALID");return;
        }
        if(!v.configured())throw error(409,"MANDATE_REQUIRED");
        var allowed=v.signers().stream().map(Signer::customerId).collect(java.util.stream.Collectors.toSet());var distinct=new LinkedHashSet<>(given);
        if(distinct.size()!=given.size()||!allowed.containsAll(distinct))throw error(409,"MANDATE_SIGNATORY_INVALID");
        if(distinct.size()<v.signaturesNeeded())throw error(409,"MANDATE_SIGNATURES_REQUIRED");
        for(UUID signer:distinct)try{customers.requireEligible(bank,signer);}catch(TenantException e){throw error(409,"MANDATE_SIGNATORY_INELIGIBLE");}
    }
    /** Account holders for statements and alerts: the primary customer, joint holders and guardians. */
    public List<UUID> holders(UUID accountId){
        return jdbc.queryForList("SELECT customer_id FROM platform.account_registry WHERE id=? UNION ALL SELECT customer_id FROM platform.account_party WHERE account_id=? AND role IN ('JOINT_HOLDER','GUARDIAN')",UUID.class,accountId,accountId);
    }
    private static TenantException error(int status,String code){return new TenantException(status,code,"Account mandate requirement not met");}
}
