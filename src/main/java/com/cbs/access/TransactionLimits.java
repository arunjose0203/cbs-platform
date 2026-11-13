package com.cbs.access;

import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bank-wide caps. Call validation while holding the tenant transaction lock. */
public final class TransactionLimits {
 private TransactionLimits() {}
 public record Change(@PositiveOrZero long expectedVersion,
  @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
  @NotNull @Pattern(regexp="FUNDING|TRANSFER|WITHDRAWAL|FUNDING_REVERSAL|HOLD|CASH_RECEIVE|CASH_DISPATCH|CASH_TRANSFER|CASH_DEPOSIT|CASH_WITHDRAWAL|CASH_SHORTAGE|CASH_OVERAGE|CASH_REVERSAL|WITHDRAWAL_REVERSAL|TRANSFER_REVERSAL|FX_CONVERSION|FX_REVERSAL|TD_OPEN|TD_REDEEM|LOAN_DISBURSE|LOAN_REPAY|TD_OPEN_REVERSAL|LOAN_DISBURSE_REVERSAL|LOAN_REPAY_REVERSAL|LOAN_FEE_ASSESS|LOAN_FEE_PAY|LOAN_FEE_WAIVE|LOAN_IMPAIR|LOAN_RESTRUCTURE|LOAN_WRITE_OFF|TD_EARLY_CLOSE|TD_RENEW|CLOSURE_SETTLEMENT|CLOSURE_SETTLEMENT_REVERSAL|LOAN_PARTIAL_WRITE_OFF|LOAN_RECOVERY|LOAN_FEE_REVERSAL|TD_PARTIAL_CLOSE") String operation,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String perTransaction,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String dailyTotal,
  @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public static void validate(Tenant bank,Change change){
  if(bank.version()!=change.expectedVersion())throw error("STALE_VERSION");
  if(bank.status()!=Status.ACTIVE)throw error("ACCOUNT_BANK_NOT_ACTIVE");
  if(!Set.of("FUNDING","TRANSFER","WITHDRAWAL","FUNDING_REVERSAL","HOLD","CASH_RECEIVE","CASH_DISPATCH","CASH_TRANSFER","CASH_DEPOSIT","CASH_WITHDRAWAL","CASH_SHORTAGE","CASH_OVERAGE","CASH_REVERSAL","WITHDRAWAL_REVERSAL","TRANSFER_REVERSAL","FX_CONVERSION","FX_REVERSAL","TD_OPEN","TD_REDEEM","LOAN_DISBURSE","LOAN_REPAY","TD_OPEN_REVERSAL","LOAN_DISBURSE_REVERSAL","LOAN_REPAY_REVERSAL","LOAN_FEE_ASSESS","LOAN_FEE_PAY","LOAN_FEE_WAIVE","LOAN_IMPAIR","LOAN_RESTRUCTURE","LOAN_WRITE_OFF","TD_EARLY_CLOSE","TD_RENEW","CLOSURE_SETTLEMENT","CLOSURE_SETTLEMENT_REVERSAL","LOAN_PARTIAL_WRITE_OFF","LOAN_RECOVERY","LOAN_FEE_REVERSAL","TD_PARTIAL_CLOSE").contains(change.operation()))throw error("LIMIT_INVALID");
  try {
   int precision=Currency.getInstance(change.currency()).getDefaultFractionDigits();
   var single=new BigDecimal(change.perTransaction());var daily=new BigDecimal(change.dailyTotal());
   if(precision<0||single.signum()<=0||daily.compareTo(single)<0||single.stripTrailingZeros().scale()>precision||daily.stripTrailingZeros().scale()>precision||single.precision()-single.scale()>18||daily.precision()-daily.scale()>18)throw error("LIMIT_INVALID");
  }catch(IllegalArgumentException exception){throw error("LIMIT_INVALID");}
 }
 public static Map<String,Object> values(Change change){return Map.of("currency",change.currency(),"operation",change.operation(),"perTransaction",change.perTransaction(),"dailyTotal",change.dailyTotal());}
 public static Map<String,Object> current(JdbcTemplate jdbc,UUID bank,String currency,String operation){
  var rows=jdbc.query("SELECT per_transaction,daily_total FROM platform.transaction_limit WHERE tenant_id=? AND currency=? AND operation=?",(r,n)->Map.<String,Object>of("currency",currency,"operation",operation,"perTransaction",r.getBigDecimal(1).toPlainString(),"dailyTotal",r.getBigDecimal(2).toPlainString()),bank,currency,operation);
  return rows.isEmpty()?Map.of():rows.getFirst();
 }
 public static void apply(JdbcTemplate jdbc,TenantRepository tenants,Actor actor,UUID bankId,Change change,String reason){
  AccessService.admin(actor);var bank=tenants.get(bankId,true);validate(bank,change);
  jdbc.update("INSERT INTO platform.transaction_limit(tenant_id,currency,operation,per_transaction,daily_total) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,currency,operation) DO UPDATE SET per_transaction=excluded.per_transaction,daily_total=excluded.daily_total",bankId,change.currency(),change.operation(),new BigDecimal(change.perTransaction()),new BigDecimal(change.dailyTotal()));
  tenants.change(bank,bank.status(),bank.displayName(),bank.defaultLanguage());
  tenants.audit(actor,bankId,"TRANSACTION_LIMIT_CHANGED",bank.version()+1,bank.status(),bank.status(),reason,null,null,values(change));
 }
 public static void check(JdbcTemplate jdbc,UUID bank,String currency,String operation,LocalDate day,BigDecimal amount){
  if(operation.equals("RELEASE"))return;
  var policies=jdbc.query("SELECT per_transaction,daily_total FROM platform.transaction_limit WHERE tenant_id=? AND currency=? AND operation=?",(r,n)->new BigDecimal[]{r.getBigDecimal(1),r.getBigDecimal(2)},bank,currency,operation);
  if(policies.isEmpty()&&operation.equals("CLOSURE_SETTLEMENT_REVERSAL"))
   policies=jdbc.query("SELECT per_transaction,daily_total FROM platform.transaction_limit WHERE tenant_id=? AND currency=? AND operation='CLOSURE_SETTLEMENT'",(r,n)->new BigDecimal[]{r.getBigDecimal(1),r.getBigDecimal(2)},bank,currency);
  // Missing configuration must never grant unlimited transaction authority.
  if(policies.isEmpty())throw error("TRANSACTION_LIMIT_REQUIRED");
  BigDecimal used;
  if(operation.equals("FUNDING"))used=jdbc.queryForObject("SELECT coalesce(sum((payload->>'amount')::numeric),0) FROM platform.funding_delivery WHERE tenant_id=? AND payload->'journal'->>'currency'=? AND payload->'journal'->>'businessDate'=?",BigDecimal.class,bank,currency,day.toString());
  else if(Set.of("TD_OPEN_REVERSAL","LOAN_DISBURSE_REVERSAL","LOAN_REPAY_REVERSAL").contains(operation))used=jdbc.queryForObject("SELECT coalesce(sum(d.amount),0) FROM platform.contract_delivery d JOIN platform.contract_reversal r ON r.id=d.id JOIN platform.contract_delivery o ON o.id=r.original_id JOIN platform.contract c ON c.id=d.contract_id WHERE d.tenant_id=? AND d.currency=? AND d.business_date=? AND c.kind=? AND o.kind=?",BigDecimal.class,bank,currency,day,operation.equals("TD_OPEN_REVERSAL")?"TERM_DEPOSIT":"LOAN",operation.equals("LOAN_REPAY_REVERSAL")?"REPAY":"OPEN");
  else if(operation.equals("TD_RENEW"))used=jdbc.queryForObject("SELECT coalesce(sum((payload->'after'->>'principal')::numeric),0) FROM platform.contract_delivery WHERE tenant_id=? AND currency=? AND business_date=? AND kind='RENEW'",BigDecimal.class,bank,currency,day);
  else if(operation.startsWith("LOAN_FEE_"))used=jdbc.queryForObject("SELECT coalesce(sum((payload->'feeServicing'->'change'->>'amount')::numeric),0) FROM platform.contract_delivery WHERE tenant_id=? AND currency=? AND business_date=? AND kind=?",BigDecimal.class,bank,currency,day,operation.substring(5));
  else if(Set.of("LOAN_IMPAIR","LOAN_RESTRUCTURE","LOAN_WRITE_OFF").contains(operation))used=jdbc.queryForObject("SELECT coalesce(sum(CASE WHEN kind='IMPAIR' THEN abs((payload->'loanServicing'->>'allowanceAfter')::numeric-(payload->'loanServicing'->>'allowanceBefore')::numeric) ELSE (payload->'before'->>'principal')::numeric+(payload->'before'->>'interest')::numeric END),0) FROM platform.contract_delivery WHERE tenant_id=? AND currency=? AND business_date=? AND kind=?",BigDecimal.class,bank,currency,day,operation.substring(5));
  else if(operation.startsWith("TD_")||operation.startsWith("LOAN_"))used=jdbc.queryForObject("SELECT coalesce(sum(d.amount),0) FROM platform.contract_delivery d JOIN platform.contract c ON c.id=d.contract_id WHERE d.tenant_id=? AND d.currency=? AND d.business_date=? AND c.kind=? AND d.kind=?",BigDecimal.class,bank,currency,day,operation.startsWith("TD_")?"TERM_DEPOSIT":"LOAN",switch(operation){case "TD_OPEN","LOAN_DISBURSE"->"OPEN";case "TD_REDEEM"->"REDEEM";case "TD_EARLY_CLOSE"->"EARLY_CLOSE";case "TD_PARTIAL_CLOSE"->"PARTIAL_CLOSE";default->"REPAY";});
  else if(operation.startsWith("FX_"))used=jdbc.queryForObject("SELECT coalesce(sum(source_amount),0) FROM platform.fx_trade WHERE tenant_id=? AND source_currency=? AND business_date=? AND (original_id IS NOT NULL)=?",BigDecimal.class,bank,currency,day,operation.equals("FX_REVERSAL"));
  else if(operation.startsWith("CASH_"))used=jdbc.queryForObject("SELECT coalesce(sum((payload->'change'->>'amount')::numeric),0) FROM platform.cash_delivery WHERE tenant_id=? AND payload->'location'->>'currency'=? AND payload->'change'->>'kind'=? AND payload->'change'->>'businessDate'=?",BigDecimal.class,bank,currency,operation.substring(5),day.toString());
  else used=jdbc.queryForObject("SELECT coalesce(sum((payload->'change'->>'amount')::numeric),0) FROM platform.account_operation WHERE tenant_id=? AND payload->'account'->'terms'->>'currency'=? AND payload->'change'->>'kind'=? AND payload->'change'->>'businessDate'=?",BigDecimal.class,bank,currency,operation,day.toString());
  enforce(policies.getFirst()[0],policies.getFirst()[1],used,amount);
 }
 public static void enforce(BigDecimal single,BigDecimal daily,BigDecimal used,BigDecimal amount){
  if(amount.signum()<=0)throw error("LIMIT_INVALID");
  if(amount.compareTo(single)>0)throw error("TRANSACTION_LIMIT_EXCEEDED");
  if(used.add(amount).compareTo(daily)>0)throw error("DAILY_LIMIT_EXCEEDED");
 }
 private static TenantException error(String code){return new TenantException(409,code,"Transaction limit validation failed");}
}
