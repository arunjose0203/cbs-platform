package com.cbs.payments;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class PaymentModels {
 private PaymentModels() {}
 public record Order(@PositiveOrZero long expectedVersion,@NotNull UUID accountId,@NotNull UUID settlementLedgerId,
  @NotBlank @Pattern(regexp="[1-9][0-9]{0,8}") String amount,
  @NotBlank @Pattern(regexp="254[17][0-9]{8}") String beneficiary,
  @NotBlank @Size(max=100) @Pattern(regexp="[^\\p{Cntrl}]+") String beneficiaryName,
  @NotNull LocalDate businessDate,@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String reference,
  @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public enum Resolution { MATCH, REFUND }
 public record Reconcile(@PositiveOrZero long expectedVersion,@NotNull UUID paymentId,@NotNull Resolution resolution,
  @NotNull UUID statementId,@NotBlank @Size(max=500) String reason) {}
 public enum Outcome { PAID, FAILED }
 public record Statement(@PositiveOrZero long expectedVersion,@NotNull UUID paymentId,
  @NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String providerReference,
  @NotBlank @Pattern(regexp="[1-9][0-9]{0,8}") String amount,
  @NotBlank @Pattern(regexp="KES") String currency,@NotBlank @Pattern(regexp="254[17][0-9]{8}") String beneficiary,
  @NotNull Outcome outcome,@NotNull LocalDate settlementDate,
  @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
  @NotBlank @Size(max=500) String reason) {}
 public record Acceptance(String conversationId,String originatorId) {}
 public record Callback(String conversationId,String originatorId,String code,String receipt,String amount) {}
}
