package com.cbs.cash;

import static com.cbs.cash.CashModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import com.cbs.access.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class DenominationService {
 public record Denomination(@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String value,@NotNull @Pattern(regexp="NOTE|COIN") String type) {}
 public record Change(@PositiveOrZero long expectedVersion,@NotNull @Pattern(regexp="[A-Z]{3}") String currency,@NotEmpty @Size(max=50) List<@NotNull @Valid Denomination> denominations,@NotBlank @Size(max=500) String reason) {}
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final com.cbs.reference.ReferenceDataService reference;
 public DenominationService(JdbcTemplate jdbc,TenantRepository tenants,com.cbs.reference.ReferenceDataService reference){this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;}
 public List<Map<String,Object>> list(UUID bank){return jdbc.queryForList("SELECT currency,value::text,type FROM platform.cash_denomination WHERE tenant_id=? ORDER BY currency,value",bank);}
 public void validate(Tenant bank,Change input){
  com.cbs.accounting.DayCloseService.requireOpen(jdbc,bank.id());
  if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");
  if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  if(reference.read(bank).currencies().stream().noneMatch(c->c.enabled()&&c.code().equals(input.currency())))throw error("CURRENCY_NOT_ENABLED");
  counted(input.currency(),input.denominations().stream().map(d->new Count(d.value(),0)).toList());
  for(var d:input.denominations())if(!Set.of("NOTE","COIN").contains(d.type()))throw error("CASH_DENOMINATION_INVALID");
  // Additive catalogue: historical and held denominations cannot silently disappear.
  for(var row:list(bank.id()))if(row.get("currency").equals(input.currency())&&input.denominations().stream().noneMatch(d->new BigDecimal(d.value()).compareTo(new BigDecimal(row.get("value").toString()))==0&&d.type().equals(row.get("type"))))throw error("CASH_DENOMINATION_IN_USE");
 }
 public Map<String,Object> values(Change input){return Map.of("currency",input.currency(),"denominations",input.denominations());}
 public void apply(Actor actor,UUID bankId,Change input){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,input);
  for(var d:input.denominations())jdbc.update("INSERT INTO platform.cash_denomination(tenant_id,currency,value,type) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",bankId,input.currency(),new BigDecimal(d.value()),d.type());
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
  tenants.audit(actor,bankId,"CASH_DENOMINATIONS_APPROVED",bank.version()+1,bank.status(),bank.status(),input.reason(),null,null,values(input));
 }
 public void requireCounts(UUID bank,String currency,List<Count> counts){
  counted(currency,counts);
  for(var c:counts)if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.cash_denomination WHERE tenant_id=? AND currency=? AND value=?)",Boolean.class,bank,currency,new BigDecimal(c.denomination())))throw error("CASH_DENOMINATION_INVALID");
 }
}
