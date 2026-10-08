package com.cbs.credit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;
import com.cbs.contracts.ContractModels;

public final class CreditModels {
 private CreditModels(){}
 public record Document(@NotBlank @Size(max=160) String reference,@NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String sha256,@NotNull LocalDate expiresOn){}
 public enum SecurityType { COLLATERAL, GUARANTEE }
 public record Security(@NotNull SecurityType type,@NotBlank @Size(max=160) String reference,@NotBlank @Size(max=160) String owner,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String value,@NotNull LocalDate valuedOn,@NotNull @Valid Document evidence){}
 public record Application(@PositiveOrZero long expectedVersion,@NotNull UUID accountId,
  @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String reference,@NotBlank @Size(max=500) String purpose,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String principal,
  @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String annualRatePercent,@Min(360) @Max(365) int dayBasis,@Min(1) @Max(120) int instalments,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String monthlyIncome,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String monthlyExpenses,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String monthlyDebtPayments,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String existingDebt,
  @NotEmpty @Size(max=20) List<@Valid Document> documents,@NotNull @Size(max=20) List<@Valid Security> securities,
  @NotBlank @Size(max=500) String reason){}
 public record Policy(@PositiveOrZero long expectedVersion,@NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String maximumPrincipal,
  @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,6})?") String maximumDebtServicePercent,
  @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String minimumMonthlySurplus,
  @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,6})?") String minimumCollateralPercent,
  @Min(1) @Max(10) int maximumRiskGrade,@Min(1) @Max(3650) int maximumValuationAgeDays,
  @NotBlank @Size(max=255) String approverSubject,@NotBlank @Size(max=160) String policyReference,@NotBlank @Size(max=500) String reason){}
 public enum Decision { APPROVE, DECLINE, REVOKE }
 public record Review(@PositiveOrZero long expectedVersion,@NotNull UUID applicationId,@NotNull Decision decision,@Min(1) @Max(10) int riskGrade,
  @NotNull LocalDate validUntil,@NotNull @Valid Document assessment,@NotNull @Size(max=20) List<@NotBlank @Size(max=300) String> conditions,@NotBlank @Size(max=500) String reason){}
 public record Release(@PositiveOrZero long expectedVersion,@NotNull UUID applicationId,@NotNull @Size(max=20) List<@Valid Document> conditionEvidence,@NotBlank @Size(max=500) String reason){}
 public record Assessment(String peakMonthlyPayment,String debtServicePercent,String monthlySurplus,String collateralPercent,String guaranteeValue){}
 public static ContractModels.Open terms(Application a,LocalDate date){return new ContractModels.Open(0,ContractModels.Type.LOAN,a.accountId(),null,null,null,null,a.principal(),a.annualRatePercent(),"0",a.dayBasis(),date,date.plusMonths(a.instalments()),a.instalments(),a.reference(),a.reason());}
 public static Assessment assess(Application a,LocalDate date,int scale){
  BigDecimal peak=ContractModels.schedule(terms(a,date),scale).stream().map(x->new BigDecimal(x.total())).max(BigDecimal::compareTo).orElseThrow();
  BigDecimal income=new BigDecimal(a.monthlyIncome()),debt=new BigDecimal(a.monthlyDebtPayments()),expenses=new BigDecimal(a.monthlyExpenses());
  if(income.signum()<=0)throw new IllegalArgumentException("Income must be positive");
  BigDecimal collateral=BigDecimal.ZERO,guarantees=BigDecimal.ZERO;
  for(var s:a.securities())if(s.type()==SecurityType.COLLATERAL)collateral=collateral.add(new BigDecimal(s.value()));else guarantees=guarantees.add(new BigDecimal(s.value()));
  return new Assessment(peak.toPlainString(),debt.add(peak).multiply(new BigDecimal("100")).divide(income,8,RoundingMode.CEILING).toPlainString(),income.subtract(expenses).subtract(debt).subtract(peak).toPlainString(),collateral.multiply(new BigDecimal("100")).divide(new BigDecimal(a.principal()),8,RoundingMode.DOWN).toPlainString(),guarantees.toPlainString());
 }
}
