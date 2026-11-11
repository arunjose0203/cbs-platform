package com.cbs.accounts;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import com.cbs.accounting.JournalModels;

public final class OperationModels {
 private OperationModels() {}
 public enum Kind { TRANSFER, WITHDRAWAL, FUNDING_REVERSAL, WITHDRAWAL_REVERSAL, TRANSFER_REVERSAL, HOLD, RELEASE, CLOSURE_SETTLEMENT }
 public record Change(@Min(0) long expectedVersion,@NotNull Kind kind,@NotNull UUID accountId,
   UUID destinationId,UUID assetLedgerId,UUID originalId,
   @NotBlank @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String amount,
   @NotNull LocalDate businessDate,@NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]+") String reference,
   @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Entry(UUID id,UUID bankId,Change change,AccountModels.Definition account,
   AccountModels.Definition destination,JournalModels.Entry journal) {}
 public record Dispatch(Entry entry,String maker,String issuer,String checker,UUID correlationId) {}
 public record Position(String posted,String held,String available,String withdrawable,boolean reconciled,UUID ledgerId) {}
 public record Item(Entry entry,String status,String failureCode,boolean cashManaged) {}
 public record Dashboard(long version,boolean storageReady,Position position,List<Item> items,long total,int limit,int offset) {}
}
