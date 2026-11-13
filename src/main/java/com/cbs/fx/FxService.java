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

 public CrossRate deriveCrossRate(Actor actor, UUID bankId, String baseCurrency, String quoteCurrency) {
  AccessService.admin(actor);
  return tx.execute(t -> {
   var bank = tenants.get(bankId, true);
   var settings = reference.read(bank);
   String pivot = bank.legalEntities().getFirst().functionalCurrency();
   Rate r1 = findActiveRateAgainstPivot(bankId, baseCurrency, pivot, settings.businessDate());
   Rate r2 = findActiveRateAgainstPivot(bankId, quoteCurrency, pivot, settings.businessDate());
   return FxModels.deriveCrossRate(r1, r2, settings.businessDate());
  });
 }

 private Rate findActiveRateAgainstPivot(UUID bankId, String base, String pivot, LocalDate date) {
  String pairKey = base.compareTo(pivot) < 0 ? base + "/" + pivot : pivot + "/" + base;
  var rows = jdbc.queryForList("SELECT payload::text FROM platform.fx_rate WHERE tenant_id = ? AND pair_key = ? AND effective_from <= ? AND effective_to >= ? AND NOT EXISTS (SELECT 1 FROM platform.fx_rate_withdrawal w WHERE w.rate_id = id) ORDER BY created_at DESC LIMIT 1", String.class, bankId, pairKey, date, date);
  if (rows.isEmpty()) throw error("FX_RATE_NOT_FOUND");
  Rate r = JSON.readValue(rows.getFirst(), Rate.class);
  if (!r.baseCurrency().equals(base)) {
   java.math.BigDecimal b = new java.math.BigDecimal(r.buyRate()), m = new java.math.BigDecimal(r.midRate()), s = new java.math.BigDecimal(r.sellRate());
   java.math.BigDecimal invBuy = java.math.BigDecimal.ONE.divide(s, 8, java.math.RoundingMode.HALF_EVEN);
   java.math.BigDecimal invMid = java.math.BigDecimal.ONE.divide(m, 8, java.math.RoundingMode.HALF_EVEN);
   java.math.BigDecimal invSell = java.math.BigDecimal.ONE.divide(b, 8, java.math.RoundingMode.HALF_EVEN);
   return new Rate(r.expectedVersion(), base, pivot, r.effectiveFrom(), r.effectiveTo(),
     FxModels.text(invBuy), FxModels.text(invMid), FxModels.text(invSell), r.expiresAt(), r.sourceReference(), r.reason());
  }
  return r;
 }

 public FxRateFeedModels.FeedRecord importFeed(Actor actor, UUID bankId, FxRateFeedModels.FeedImport input) {
  AccessService.admin(actor);
  return tx.execute(t -> {
   var bank = tenants.get(bankId, true);
   UUID id = UUID.randomUUID();
   jdbc.update("INSERT INTO platform.fx_rate_feed(id, tenant_id, feed_source, effective_date, total_rates, payload, status, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'STAGED', ?)",
     id, bankId, input.feedSource(), input.effectiveDate(), input.items().size(), JSON.writeValueAsString(input.items()), actor.subject());
   return new FxRateFeedModels.FeedRecord(id, bankId, input.feedSource(), input.effectiveDate(),
     input.items().size(), input.items(), "STAGED", actor.subject(), Instant.now());
  });
 }

 public List<FxRateFeedModels.FeedRecord> listFeeds(Actor actor, UUID bankId) {
  AccessService.admin(actor);
  return jdbc.query("SELECT id, tenant_id, feed_source, effective_date, total_rates, payload, status, created_by, created_at FROM platform.fx_rate_feed WHERE tenant_id = ? ORDER BY created_at DESC", (rs, rowNum) -> new FxRateFeedModels.FeedRecord(
    rs.getObject("id", UUID.class),
    rs.getObject("tenant_id", UUID.class),
    rs.getString("feed_source"),
    rs.getDate("effective_date").toLocalDate(),
    rs.getInt("total_rates"),
    JSON.readValue(rs.getString("payload"), new tools.jackson.core.type.TypeReference<List<FxRateFeedModels.FeedItem>>() {}),
    rs.getString("status"),
    rs.getString("created_by"),
    rs.getTimestamp("created_at").toInstant()
  ), bankId);
 }

 public void approveFeed(Actor actor, UUID bankId, UUID feedId) {
  AccessService.admin(actor);
  tx.executeWithoutResult(t -> {
   var bank = tenants.get(bankId, true);
   var feedRows = jdbc.query("SELECT status, created_by, payload FROM platform.fx_rate_feed WHERE tenant_id = ? AND id = ? FOR UPDATE", (rs, rowNum) -> Map.of(
     "status", rs.getString("status"),
     "createdBy", rs.getString("created_by"),
     "payload", rs.getString("payload")
   ), bankId, feedId);
   if (feedRows.isEmpty()) throw error("FEED_NOT_FOUND");
   var feed = feedRows.getFirst();
   if (!feed.get("status").equals("STAGED")) throw error("FEED_NOT_STAGED");
   if (feed.get("createdBy").equals(actor.subject())) throw new TenantException(409, "MAKER_CHECKER_VIOLATION", "Reviewer must differ from feed creator");

   List<FxRateFeedModels.FeedItem> items = JSON.readValue((String) feed.get("payload"), new tools.jackson.core.type.TypeReference<List<FxRateFeedModels.FeedItem>>() {});
   for (var item : items) {
    Rate r = new Rate(bank.version(), item.baseCurrency(), item.quoteCurrency(), item.effectiveFrom(), item.effectiveTo(),
      item.buyRate(), item.midRate(), item.sellRate(), item.expiresAt(), item.sourceReference(), "Imported from feed " + feedId);
    validate(bank, r);
    UUID rateApproval = UUID.randomUUID();
    apply(actor, bankId, r, rateApproval);
   }
   jdbc.update("UPDATE platform.fx_rate_feed SET status = 'APPROVED' WHERE id = ?", feedId);
  });
 }

 private static TenantException error(String code){return new TenantException(409,code,"FX configuration requires attention");}
}
