package com.cbs.payments;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class StatementImportModels {
 private StatementImportModels() {}
 public record Upload(@PositiveOrZero long expectedVersion,@NotNull UUID ledgerId,
  @NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String statementReference,
  @NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String walletReference,
  @NotNull LocalDate fromDate,@NotNull LocalDate toDate,
  @NotBlank String openingBalance,@NotBlank String closingBalance,
  @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
  @NotNull @Size(max=9) Map<String,String> columns,@NotBlank @Size(max=1048576) String csv) {}
 public enum Type { PAYOUT, FEE, CREDIT, DEBIT, REVERSAL }
 public record Line(int number,String receipt,LocalDate date,Type type,String credit,String debit,String balance,String paymentReference,String beneficiary) {}
 public record Source(UUID ledgerId,String statementReference,String walletReference,LocalDate fromDate,LocalDate toDate,String openingBalance,String closingBalance,String evidenceReference,String fileHash,Map<String,String> columns) {}
 public enum Kind { MATCH, RETURN, LINK, UNLINK, BOOK, REVERSE, DUPLICATE, CLEAR_CONFLICT, CLOSE, REOPEN, SUPERSEDE }
 public record Action(@PositiveOrZero long expectedVersion,@NotNull UUID batchId,@NotNull Kind kind,
  UUID lineId,UUID relatedId,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String reportHash,
  @NotBlank @Size(max=160) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]*") String evidenceReference,
  @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
}
