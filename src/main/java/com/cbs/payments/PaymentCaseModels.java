package com.cbs.payments;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class PaymentCaseModels {
 private PaymentCaseModels(){}
 public enum Kind { SETTLEMENT_DELAY, RETURNED_PAYMENT, PROVIDER_REVERSAL, DISPUTE }
 public enum Decision { ASSIGN, CARRY, RESOLVE, CANCEL }
 public record Open(@PositiveOrZero long expectedVersion,@NotNull UUID paymentId,@NotNull Kind kind,UUID statementId,UUID lineId,
  @NotNull LocalDate dueDate,@NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Change(@PositiveOrZero long expectedVersion,@NotNull UUID caseId,@PositiveOrZero long caseVersion,@NotNull Decision decision,
  UUID ownerId,@NotNull LocalDate dueDate,@NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Note(@PositiveOrZero long caseVersion,@NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,@NotBlank @Size(max=1000) @Pattern(regexp="[^\\p{Cntrl}]+") String note) {}
}
