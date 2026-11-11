package com.cbs.servicing;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import com.cbs.accounting.JournalModels;
import com.cbs.accounts.AccountModels;

public final class ServicingModels {
 private ServicingModels() {}
 public record Policy(@PositiveOrZero long expectedVersion,@NotNull UUID productId,@Min(1) int productRevision,@NotNull UUID legalEntityId,
  @NotNull LocalDate effectiveFrom,@NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String annualRatePercent,
  @Min(360) @Max(365) int dayBasis,@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String feePerBusinessDay,
  @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String interestTaxPercent,@NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String feeTaxPercent,
  @NotNull @Pattern(regexp="HALF_EVEN|HALF_UP|DOWN") String roundingMode,@NotNull UUID interestExpenseLedger,@NotNull UUID interestPayableLedger,
  @NotNull UUID feeIncomeLedger,@NotNull UUID taxPayableLedger,@NotBlank @Size(max=500) String reason) {}
 public record Run(@PositiveOrZero long expectedVersion,@NotNull LocalDate businessDate,@NotBlank @Size(max=500) String reason) {}
 public record Calculation(String exactAccrual,String grossInterest,String interestTax,String fee,String feeTax,String netChange,String carryAfter) {}
 public record Item(UUID id,UUID policyId,Policy policy,AccountModels.Definition account,String openingBalance,String held,String carryBefore,Calculation calculation,JournalModels.Entry journal) {}
 /**
  * Run header. Legacy runs carry every item inline (chunkCount null). Chunked runs keep
  * items in separately posted chunks; null fields are omitted so legacy payloads stay byte-identical.
  */
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Batch(UUID id,UUID bankId,LocalDate businessDate,LocalDate nextBusinessDate,List<Item> items,Integer itemCount,Integer chunkCount,Integer excludedCount) {
  public Batch(UUID id,UUID bankId,LocalDate businessDate,LocalDate nextBusinessDate,List<Item> items){this(id,bankId,businessDate,nextBusinessDate,items,null,null,null);}
  public boolean chunked(){return chunkCount!=null;}
 }
 public record Chunk(UUID runId,int number,List<Item> items) {}
 /** An account deliberately left out of one day's run, approved as part of the run. */
 public record Exclusion(UUID accountId,String accountNumber,String reason) {}
 public record Totals(String currency,int accounts,String grossInterest,String interestTax,String fee,String feeTax,String netChange) {}
 public record Preview(UUID id,LocalDate businessDate,LocalDate nextBusinessDate,int accountCount,int chunkSize,int chunkCount,List<Totals> totals,List<Exclusion> exclusions,List<Item> items,boolean itemsTruncated) {}
 /** Longest interval one run may cover (calendar days to the next working date). */
 public static final int MAX_PERIOD_DAYS=366;
 public record Dispatch(Batch batch,String maker,String issuer,String checker,UUID correlationId) {}
 public static Calculation calculate(Policy p,String currency,BigDecimal balance,BigDecimal carry,int days){
  if(days<1||days>MAX_PERIOD_DAYS||balance.signum()<0||!Set.of(360,365).contains(p.dayBasis()))throw new IllegalArgumentException("Invalid accrual period");
  for(String rate:List.of(p.annualRatePercent(),p.interestTaxPercent(),p.feeTaxPercent()))if(new BigDecimal(rate).signum()<0||new BigDecimal(rate).compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Invalid rate");
  int scale=Currency.getInstance(currency).getDefaultFractionDigits();if(scale<0)throw new IllegalArgumentException("Invalid currency");
  var mode=RoundingMode.valueOf(p.roundingMode());
  var exact=balance.multiply(new BigDecimal(p.annualRatePercent())).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(100L*p.dayBasis()),18,RoundingMode.HALF_EVEN);
  var gross=exact.add(carry).setScale(scale,mode).max(BigDecimal.ZERO);
  var tax=gross.multiply(new BigDecimal(p.interestTaxPercent())).divide(new BigDecimal("100"),scale,mode);
  var fee=new BigDecimal(p.feePerBusinessDay()).setScale(scale,RoundingMode.UNNECESSARY);if(fee.signum()<0)throw new IllegalArgumentException("Invalid fee");
  var feeTax=fee.multiply(new BigDecimal(p.feeTaxPercent())).divide(new BigDecimal("100"),scale,mode);
  return new Calculation(text(exact),text(gross),text(tax),text(fee),text(feeTax),text(gross.subtract(tax).subtract(fee).subtract(feeTax)),text(exact.add(carry).subtract(gross)));
 }
 public static String text(BigDecimal amount){return amount.stripTrailingZeros().toPlainString();}
}
