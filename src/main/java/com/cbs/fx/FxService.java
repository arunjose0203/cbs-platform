package com.cbs.fx;

import static com.cbs.fx.FxModels.*;
import com.cbs.access.AccessService;
import com.cbs.accounting.DayCloseService;
import com.cbs.reference.ReferenceDataService;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class FxService {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate jdbc;private final TenantRepository tenants;private final ReferenceDataService reference;private final TransactionTemplate tx;
 public FxService(JdbcTemplate jdbc,TenantRepository tenants,ReferenceDataService reference,PlatformTransactionManager manager){this.jdbc=jdbc;this.tenants=tenants;this.reference=reference;this.tx=new TransactionTemplate(manager);}
 public Map<String,Object> dashboard(Actor actor,UUID bank,int limit,int offset){AccessService.admin(actor);AccessService.page(limit,offset);return tx.execute(t->{var b=tenants.get(bank,true);var settings=reference.read(b);return Map.of("version",b.version(),"businessDate",settings.businessDate(),"currencies",settings.currencies().stream().filter(c->c.enabled()).toList(),"items",jdbc.query("SELECT r.*,EXISTS(SELECT 1 FROM platform.fx_rate_withdrawal w WHERE w.rate_id=r.id) withdrawn FROM platform.fx_rate r WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->{var value=JSON.readValue(r.getString("payload"),Rate.class);String status=r.getBoolean("withdrawn")?"WITHDRAWN":!value.expiresAt().isAfter(Instant.now())||value.effectiveTo().isBefore(settings.businessDate())?"EXPIRED":value.effectiveFrom().isAfter(settings.businessDate())?"SCHEDULED":"ACTIVE";return Map.of("id",r.getObject("id",UUID.class),"rate",value,"status",status,"approvedBy",r.getString("reviewer_subject"),"approvedAt",r.getTimestamp("created_at").toInstant());},bank,limit,offset),"total",jdbc.queryForObject("SELECT count(*) FROM platform.fx_rate WHERE tenant_id=?",Long.class,bank));});}
 public void validate(Tenant bank,Rate r){DayCloseService.requireOpen(jdbc,bank.id());if(bank.version()!=r.expectedVersion())throw error("STALE_VERSION");if(bank.status()!=Status.ACTIVE)throw error("LEDGER_BANK_NOT_ACTIVE");try{FxModels.validateRates(r);}catch(IllegalArgumentException e){throw error("FX_RATE_INVALID");}var s=reference.read(bank);for(var c:List.of(r.baseCurrency(),r.quoteCurrency()))if(s.currencies().stream().noneMatch(x->x.code().equals(c)&&x.enabled()))throw error("CURRENCY_NOT_ENABLED");
  if(r.effectiveFrom()==null||r.effectiveTo()==null||r.effectiveFrom().isBefore(s.businessDate())||r.effectiveTo().isBefore(r.effectiveFrom())||r.effectiveTo().isAfter(r.effectiveFrom().plusYears(1))||r.expiresAt()==null||!r.expiresAt().isAfter(Instant.now()))throw error("FX_RATE_PERIOD_INVALID");
  if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_rate r WHERE tenant_id=? AND pair_key=? AND effective_from<=? AND effective_to>=? AND NOT EXISTS(SELECT 1 FROM platform.fx_rate_withdrawal w WHERE w.rate_id=r.id))",Boolean.class,bank.id(),pair(r),r.effectiveTo(),r.effectiveFrom()))throw error("FX_RATE_OVERLAP");
 }
 private String pair(Rate r){return r.baseCurrency().compareTo(r.quoteCurrency())<0?r.baseCurrency()+"/"+r.quoteCurrency():r.quoteCurrency()+"/"+r.baseCurrency();}
 public Map<String,Object> values(Rate r){return Map.of("rate",r,"quotation","1 "+r.baseCurrency()+" = quote currency units; buy/sell are from the bank's perspective","posting","Rate approval does not move money");}
 public void apply(Actor actor,UUID bankId,Rate r,UUID approval){AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,r);jdbc.update("INSERT INTO platform.fx_rate(id,tenant_id,pair_key,effective_from,effective_to,payload,reviewer_subject) VALUES (?,?,?,?,?,?::jsonb,?)",approval,bankId,pair(r),r.effectiveFrom(),r.effectiveTo(),JSON.writeValueAsString(r),actor.subject());changed(actor,bank,"FX_RATE_APPROVED",approval,r.reason());}
 public void validateWithdrawal(Tenant bank,Withdraw input){if(bank.version()!=input.expectedVersion())throw error("STALE_VERSION");rate(bank.id(),input.rateId());if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_rate_withdrawal WHERE rate_id=?)",Boolean.class,input.rateId()))throw error("FX_RATE_UNAVAILABLE");}
 public void withdraw(Actor actor,UUID bankId,Withdraw input,UUID approval){AccessService.admin(actor);var bank=tenants.get(bankId,true);validateWithdrawal(bank,input);jdbc.update("INSERT INTO platform.fx_rate_withdrawal(rate_id,tenant_id,approval_id) VALUES (?,?,?)",input.rateId(),bankId,approval);changed(actor,bank,"FX_RATE_WITHDRAWN",approval,input.reason());}
 public Rate rate(UUID bank,UUID id){var rows=jdbc.queryForList("SELECT payload::text FROM platform.fx_rate WHERE tenant_id=? AND id=?",String.class,bank,id);if(rows.isEmpty())throw error("FX_RATE_NOT_FOUND");return JSON.readValue(rows.getFirst(),Rate.class);}
 public Quote quote(Actor actor,UUID bankId,QuoteInput input){AccessService.admin(actor);return tx.execute(t->{var bank=tenants.get(bankId,true);return quote(bank,input);});}
 public Quote quote(Tenant bank,QuoteInput input){var rate=rate(bank.id(),input.rateId());var s=reference.read(bank);if(bank.status()!=Status.ACTIVE||s.businessDate().isBefore(rate.effectiveFrom())||s.businessDate().isAfter(rate.effectiveTo())||!rate.expiresAt().isAfter(Instant.now())||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.fx_rate_withdrawal WHERE rate_id=?)",Boolean.class,input.rateId()))throw error("FX_RATE_UNAVAILABLE");for(var currency:List.of(rate.baseCurrency(),rate.quoteCurrency()))if(s.currencies().stream().noneMatch(c->c.code().equals(currency)&&c.enabled()))throw error("CURRENCY_NOT_ENABLED");String target=input.side()==Side.BANK_BUYS_BASE?rate.quoteCurrency():rate.baseCurrency();var rounding=s.currencies().stream().filter(c->c.code().equals(target)).findFirst().orElseThrow().roundingMode();try{return calculate(input.rateId(),rate,input.side(),input.sourceAmount(),rounding,s.businessDate());}catch(IllegalArgumentException e){throw error("FX_AMOUNT_INVALID");}}
 private void changed(Actor actor,Tenant bank,String action,UUID id,String reason){tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());tenants.audit(actor,bank.id(),action,bank.version()+1,bank.status(),bank.status(),reason,null,null,Map.of("requestId",id));}
 private static TenantException error(String code){return new TenantException(409,code,"FX configuration requires attention");}
}
