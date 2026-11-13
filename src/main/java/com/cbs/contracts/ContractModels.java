package com.cbs.contracts;

import com.cbs.accounts.AccountModels;
import com.cbs.accounting.JournalModels;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Fixed-rate contracts. Money never passes through binary floating-point arithmetic. */
public final class ContractModels {
    private ContractModels() {}
    public enum Type { TERM_DEPOSIT, LOAN }
    public enum Kind { OPEN, ACCRUE, REPAY, REDEEM, REVERSE, EARLY_CLOSE, RENEW, RESTRUCTURE, IMPAIR, WRITE_OFF, FEE_ASSESS, FEE_PAY, FEE_WAIVE, PARTIAL_WRITE_OFF, RECOVERY, PARTIAL_CLOSE }
    public record Open(@PositiveOrZero long expectedVersion,@NotNull Type type,@NotNull UUID accountId,
        @NotNull UUID principalLedger,@NotNull UUID accruedInterestLedger,@NotNull UUID interestProfitLossLedger,UUID taxLedger,
        @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String principal,
        @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String annualRatePercent,
        @NotNull @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String withholdingPercent,
        @Min(360) @Max(365) int dayBasis,@NotNull LocalDate startDate,@NotNull LocalDate maturityDate,
        @Min(0) @Max(120) int instalments,@NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String agreementReference,
        @NotBlank @Size(max=500) String reason) {}
    public record Event(@PositiveOrZero long expectedVersion,@NotNull UUID contractId,@NotNull Kind kind,
        @NotNull LocalDate businessDate,@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String amount,
        @NotBlank @Size(max=500) String reason) {}
    public record Correction(@PositiveOrZero long expectedVersion,@NotNull UUID contractId,@NotNull UUID originalEventId,
        @NotNull LocalDate businessDate,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record Definition(UUID id,UUID bankId,AccountModels.Definition account,Open terms) {}
    public record Position(String principal,String interest,String carry,LocalDate accruedThrough,boolean closed) {}
    public record Instalment(int number,LocalDate dueDate,String principal,String projectedInterest,String total) {}
    public record Calculation(String interest,String carry) {}
    public record Posting(UUID id,Definition contract,Kind kind,LocalDate businessDate,Position before,Position after,
        String customerMovement,String tax,JournalModels.Entry journal,String reason,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID originalEventId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) TermServicingModels.Details servicing,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) LoanServicingModels.Details loanServicing,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) LoanFeeModels.Details feeServicing) {
        public Posting(UUID id,Definition contract,Kind kind,LocalDate businessDate,Position before,Position after,String customerMovement,String tax,JournalModels.Entry journal,String reason,UUID originalEventId,TermServicingModels.Details servicing,LoanServicingModels.Details loanServicing){this(id,contract,kind,businessDate,before,after,customerMovement,tax,journal,reason,originalEventId,servicing,loanServicing,null);}
        public Posting(UUID id,Definition contract,Kind kind,LocalDate businessDate,Position before,Position after,String customerMovement,String tax,JournalModels.Entry journal,String reason,UUID originalEventId,TermServicingModels.Details servicing){this(id,contract,kind,businessDate,before,after,customerMovement,tax,journal,reason,originalEventId,servicing,null);}
        public Posting(UUID id,Definition contract,Kind kind,LocalDate businessDate,Position before,Position after,
                String customerMovement,String tax,JournalModels.Entry journal,String reason) {
            this(id,contract,kind,businessDate,before,after,customerMovement,tax,journal,reason,null,null,null);
        }
        public Posting(UUID id,Definition contract,Kind kind,LocalDate businessDate,Position before,Position after,
                String customerMovement,String tax,JournalModels.Entry journal,String reason,UUID originalEventId) {
            this(id,contract,kind,businessDate,before,after,customerMovement,tax,journal,reason,originalEventId,null,null);
        }
    }
    public record Dispatch(Posting posting,String maker,String issuer,String checker,UUID correlationId) {}

    public static int scale(Definition d){return Currency.getInstance(d.account().terms().currency()).getDefaultFractionDigits();}
    public static BigDecimal decimal(String value){return new BigDecimal(value);}
    public static String text(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    public static Calculation interest(String principal,String annualRate,int basis,LocalDate from,LocalDate through,String carry,int scale){
        long days=ChronoUnit.DAYS.between(from,through);
        if(days<0||days>3660||(basis!=360&&basis!=365))throw new IllegalArgumentException("Invalid accrual period");
        BigDecimal exact=decimal(principal).multiply(decimal(annualRate)).multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(basis*100L),24,RoundingMode.HALF_EVEN).add(decimal(carry));
        BigDecimal rounded=exact.setScale(scale,RoundingMode.HALF_EVEN);
        return new Calculation(text(rounded),text(exact.subtract(rounded)));
    }
    /** Equal principal, actual-day interest on reducing principal; projected dates are contractual calendar dates. */
    public static List<Instalment> schedule(Open terms,int scale){
        if(terms.type()!=Type.LOAN)return List.of();
        if(terms.instalments()<1||terms.instalments()>120||!terms.maturityDate().equals(terms.startDate().plusMonths(terms.instalments())))throw new IllegalArgumentException("Loan maturity must match monthly instalments");
        BigDecimal remaining=decimal(terms.principal()),each=remaining.divide(BigDecimal.valueOf(terms.instalments()),scale,RoundingMode.DOWN);
        if(each.signum()<=0)throw new IllegalArgumentException("Principal too small for schedule");
        List<Instalment> rows=new ArrayList<>();LocalDate from=terms.startDate();String carry="0";
        for(int n=1;n<=terms.instalments();n++){
            LocalDate due=terms.startDate().plusMonths(n);BigDecimal principal=n==terms.instalments()?remaining:each;
            var interest=interest(text(remaining),terms.annualRatePercent(),terms.dayBasis(),from,due,carry,scale);carry=interest.carry();
            rows.add(new Instalment(n,due,text(principal),interest.interest(),text(principal.add(decimal(interest.interest())))));
            remaining=remaining.subtract(principal);from=due;
        }
        return List.copyOf(rows);
    }
}
