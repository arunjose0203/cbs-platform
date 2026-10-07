package com.cbs.accounts;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import com.cbs.accounting.JournalModels;
public final class FundingModels {
 private FundingModels() {}
 public record Change(@Min(0) long expectedVersion,@NotNull UUID accountId,@NotNull UUID sourceLedgerId,@NotNull UUID liabilityLedgerId,@NotBlank @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String amount,@NotNull LocalDate businessDate,@NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]+") String reference,@NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
 public record Entry(UUID id,UUID bankId,UUID accountId,UUID liabilityLedgerId,String amount,JournalModels.Entry journal,AccountModels.Definition account) {}
 public record Dispatch(Entry entry,String maker,String issuer,String checker,UUID correlationId) {}
 public record Balance(UUID accountId,String currency,String postedBalance,String ledgerBalance,boolean reconciled,UUID liabilityLedgerId) {}
 public record Item(Entry entry,String status,String failureCode) {}
 public record Dashboard(long version,boolean storageReady,AccountModels.Detail account,Balance balance,List<Item> items,long total,int limit,int offset) {}
}
