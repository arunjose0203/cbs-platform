package com.cbs.fx;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class FxModels {
 private FxModels(){}
 public enum Side { BANK_BUYS_BASE, BANK_SELLS_BASE }
 public record Rate(@PositiveOrZero long expectedVersion,@NotNull @Pattern(regexp="[A-Z]{3}") String baseCurrency,
  @NotNull @Pattern(regexp="[A-Z]{3}") String quoteCurrency,@NotNull LocalDate effectiveFrom,@NotNull LocalDate effectiveTo,
  @NotNull @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,12})?") String buyRate,@NotNull @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,12})?") String midRate,
  @NotNull @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,12})?") String sellRate,@NotNull Instant expiresAt,
  @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String sourceReference,
  @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Withdraw(@PositiveOrZero long expectedVersion,@NotNull UUID rateId,@NotBlank @Size(max=500) String reason){}
 public record QuoteInput(@NotNull UUID rateId,@NotNull Side side,@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String sourceAmount){}
 public record Quote(UUID rateId,Side side,String sourceCurrency,String targetCurrency,String sourceAmount,String targetAmount,String appliedRate,String roundingMode,String roundingDifference,LocalDate businessDate,Instant expiresAt){}
 public static Quote calculate(UUID id,Rate rate,Side side,String amount,String rounding,LocalDate date){
  validateRates(rate);if(side==null)throw new IllegalArgumentException("Missing side");
  String source=side==Side.BANK_BUYS_BASE?rate.baseCurrency():rate.quoteCurrency(),target=side==Side.BANK_BUYS_BASE?rate.quoteCurrency():rate.baseCurrency();
  BigDecimal input=money(amount,source),price=new BigDecimal(side==Side.BANK_BUYS_BASE?rate.buyRate():rate.sellRate());
  var exact=side==Side.BANK_BUYS_BASE?input.multiply(price):input.divide(price,30,RoundingMode.HALF_EVEN);
  int scale=Currency.getInstance(target).getDefaultFractionDigits();
  var output=side==Side.BANK_BUYS_BASE?exact.setScale(scale,RoundingMode.valueOf(rounding)):input.divide(price,scale,RoundingMode.valueOf(rounding));
  if(output.signum()<=0||output.precision()-output.scale()>18)throw new IllegalArgumentException("Conversion out of range");
  return new Quote(id,side,source,target,text(input),text(output),text(price),rounding,text(exact.subtract(output)),date,rate.expiresAt());
 }
 public static BigDecimal money(String value,String currency){if(value==null||!value.matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))throw new IllegalArgumentException("Invalid amount");var n=new BigDecimal(value);int scale=Currency.getInstance(currency).getDefaultFractionDigits();if(scale<0||n.signum()<=0||n.stripTrailingZeros().scale()>scale)throw new IllegalArgumentException("Invalid currency precision");return n;}
 public static void validateRates(Rate r){if(r.baseCurrency().equals(r.quoteCurrency())||Currency.getInstance(r.baseCurrency()).getDefaultFractionDigits()<0||Currency.getInstance(r.quoteCurrency()).getDefaultFractionDigits()<0)throw new IllegalArgumentException("Invalid pair");var bid=new BigDecimal(r.buyRate());var mid=new BigDecimal(r.midRate());var ask=new BigDecimal(r.sellRate());for(var value:List.of(r.buyRate(),r.midRate(),r.sellRate()))if(!value.matches("[0-9]{1,12}(\\.[0-9]{1,12})?"))throw new IllegalArgumentException("Invalid rate");if(bid.signum()<=0||bid.compareTo(mid)>0||mid.compareTo(ask)>0)throw new IllegalArgumentException("Require buy <= mid <= sell");}
 public static String text(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
