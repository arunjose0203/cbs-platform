package com.cbs.fx;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.LocalDate;
import com.cbs.accounts.AccountModels;
import com.cbs.accounting.JournalModels;
public final class FxTradeModels {
 private FxTradeModels(){}
 public record Trade(@PositiveOrZero long expectedVersion,@NotNull UUID rateId,@NotNull FxModels.Side side,@NotNull UUID sourceAccountId,@NotNull UUID targetAccountId,@NotNull UUID sourcePositionLedger,@NotNull UUID targetPositionLedger,@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String sourceAmount,@NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String expectedTargetAmount,@NotNull LocalDate businessDate,@NotBlank @Size(max=500) String reason){}
 public record Reversal(@PositiveOrZero long expectedVersion,@NotNull UUID originalId,@NotNull LocalDate businessDate,@NotBlank @Size(max=500) String reason){}
 public record Batch(UUID id,UUID bankId,UUID originalId,AccountModels.Definition source,AccountModels.Definition target,FxModels.Quote quote,JournalModels.Entry sourceJournal,JournalModels.Entry targetJournal){}
 public record Dispatch(Batch batch,String maker,String issuer,String checker,UUID correlationId){}
}
