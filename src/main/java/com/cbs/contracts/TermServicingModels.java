package com.cbs.contracts;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class TermServicingModels {
    private TermServicingModels() { }
    public enum Operation { EARLY_CLOSE, RENEW, PARTIAL_CLOSE }
    public enum InterestDisposition { PAY_OUT_INTEREST, CAPITALIZE_NET_INTEREST }
    public enum TaxBasis { GROSS_INTEREST, INTEREST_AFTER_PENALTY }
    public record Instruction(@PositiveOrZero long expectedVersion,@NotNull UUID contractId,
            @NotNull Operation operation,@NotNull LocalDate businessDate,
            @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String penaltyAmount,
            UUID penaltyIncomeLedger,@NotNull TaxBasis taxBasis,InterestDisposition interestDisposition,
            @Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,8})?") String renewalAnnualRatePercent,
            LocalDate renewalMaturityDate,
            @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String agreementReference,
            @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason,
            @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String closeAmount) {
        public Instruction(long expectedVersion,UUID contractId,Operation operation,LocalDate businessDate,
                String penaltyAmount,UUID penaltyIncomeLedger,TaxBasis taxBasis,InterestDisposition interestDisposition,
                String renewalAnnualRatePercent,LocalDate renewalMaturityDate,String agreementReference,String reason) {
            this(expectedVersion,contractId,operation,businessDate,penaltyAmount,penaltyIncomeLedger,taxBasis,
                    interestDisposition,renewalAnnualRatePercent,renewalMaturityDate,agreementReference,reason,null);
        }
    }
    public record Details(Instruction instruction,ContractModels.Open renewedTerms,String penalty) { }
}
