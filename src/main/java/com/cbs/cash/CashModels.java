package com.cbs.cash;

import com.cbs.accounting.JournalModels;
import com.cbs.tenancy.TenantException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class CashModels {
 private CashModels() {}
 public enum Kind { REGISTER, RECEIVE, DISPATCH, TRANSFER, OPEN, CLOSE, COUNT, DEPOSIT, WITHDRAWAL, SHORTAGE, OVERAGE }
 public enum Type { VAULT, TILL }
 public record Count(@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String denomination,
                     @Min(0) @Max(1000000000) long quantity) {}
 public record Change(@PositiveOrZero long expectedVersion,@NotNull Kind kind,@NotNull UUID branchId,
   UUID locationId,UUID destinationId,UUID ledgerId,
   @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,31}") String code,
   @Size(max=160) @Pattern(regexp="[^\\p{Cntrl}]+") String name,
   @Pattern(regexp="[A-Z]{3}") String currency,Type type,
   @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String amount,
   @NotNull LocalDate businessDate,
   @NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]+") String reference,
   @Size(max=50) List<@NotNull @Valid Count> counts,
   @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason,UUID accountId,UUID liabilityLedgerId,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID exceptionId) {
  public Change(long expectedVersion,Kind kind,UUID branchId,UUID locationId,UUID destinationId,UUID ledgerId,String code,String name,String currency,Type type,String amount,LocalDate businessDate,String reference,List<Count> counts,String reason,UUID accountId,UUID liabilityLedgerId){this(expectedVersion,kind,branchId,locationId,destinationId,ledgerId,code,name,currency,type,amount,businessDate,reference,counts,reason,accountId,liabilityLedgerId,null); }
  public Change(long expectedVersion,Kind kind,UUID branchId,UUID locationId,UUID destinationId,UUID ledgerId,String code,String name,String currency,Type type,String amount,LocalDate businessDate,String reference,List<Count> counts,String reason){this(expectedVersion,kind,branchId,locationId,destinationId,ledgerId,code,name,currency,type,amount,businessDate,reference,counts,reason,null,null);}
 }
 public record Location(UUID id,UUID bankId,UUID branchId,UUID ledgerId,String code,String name,String currency,Type type,UUID legalEntityId) {}
 public record Position(Location location,String balance,String ledgerBalance,boolean reconciled,boolean sessionOpen,String custodian,LocalDate sessionDate,LocalDate countedDate) {}
 public record Entry(UUID id,UUID bankId,Change change,Location location,Location destination,JournalModels.Entry journal,com.cbs.accounts.FundingModels.Entry funding,com.cbs.accounts.OperationModels.Entry operation) {}
 public record Dispatch(Entry entry,String maker,String issuer,String checker,UUID correlationId) {}
 public static boolean adjustment(Kind kind){return kind==Kind.SHORTAGE||kind==Kind.OVERAGE;}
 public static boolean monetary(Kind kind){return adjustment(kind)|| kind==Kind.RECEIVE||kind==Kind.DISPATCH||kind==Kind.TRANSFER||kind==Kind.DEPOSIT||kind==Kind.WITHDRAWAL;}
 public static BigDecimal counted(String currency,List<Count> counts){
  if(counts==null||counts.size()>50)throw error("CASH_COUNT_INVALID");
  int scale=Currency.getInstance(currency).getDefaultFractionDigits();
  var seen=new HashSet<BigDecimal>();var total=BigDecimal.ZERO;
  for(var count:counts){
   if(count==null||count.denomination()==null||!count.denomination().matches("[0-9]{1,18}(\\.[0-9]{1,6})?")||count.quantity()<0||count.quantity()>1000000000)throw error("CASH_COUNT_INVALID");
   var denomination=new BigDecimal(count.denomination()).stripTrailingZeros();
   if(denomination.signum()<=0||scale<0||denomination.scale()>scale||!seen.add(denomination))throw error("CASH_COUNT_INVALID");
   total=total.add(denomination.multiply(BigDecimal.valueOf(count.quantity())));
  }
  if(total.compareTo(new BigDecimal("1000000000000000000"))>=0)throw error("CASH_COUNT_INVALID");
  return total;
 }
 public static TenantException error(String code){return new TenantException(409,code,"Cash custody operation could not be completed");}
}
