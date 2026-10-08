package com.cbs.fx;

import com.cbs.accounting.JournalModels;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Signed quantities and costs are positive for a long position, negative for a short. */
public final class FxValuationModels {
    private FxValuationModels() { }
    public enum CostBasis { WEIGHTED_AVERAGE }
    public record Policy(@PositiveOrZero long expectedVersion, @NotNull UUID legalEntityId,
            @NotNull @Pattern(regexp="[A-Z]{3}") String foreignCurrency,
            @NotNull UUID foreignPositionLedger, @NotNull UUID functionalPositionLedger,
            @NotNull UUID realizedGainLedger, @NotNull UUID realizedLossLedger,
            @NotNull UUID unrealizedGainLedger, @NotNull UUID unrealizedLossLedger,
            @NotNull CostBasis costBasis,
            @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String longLimit,
            @NotNull @Pattern(regexp="[0-9]{1,18}(\\.[0-9]{1,6})?") String shortLimit,
            @NotBlank @Size(max=160) String policyReference,
            @NotBlank @Size(max=500) String reason) {
        public List<UUID> ledgers() { return List.of(foreignPositionLedger,functionalPositionLedger,
                realizedGainLedger,realizedLossLedger,unrealizedGainLedger,unrealizedLossLedger); }
    }
    public record Book(UUID id, UUID bankId, String functionalCurrency, Policy policy) { }
    public record ClosingRate(@PositiveOrZero long expectedVersion, @NotNull UUID bookId,
            @NotNull LocalDate businessDate,
            @NotNull @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,12})?") String rate,
            @NotBlank @Size(max=160) String sourceReference, @NotBlank @Size(max=500) String reason) { }
    public record Run(@PositiveOrZero long expectedVersion, @NotNull UUID bookId,
            @NotNull UUID closingRateId, @NotNull LocalDate businessDate,
            @NotBlank @Size(max=500) String reason) { }
    public record Flow(BigDecimal quantity, BigDecimal functionalCash) { }
    public record Position(BigDecimal quantity, BigDecimal cost, BigDecimal cash) { }
    public record Totals(String quantity, String cost, String cash, String marketValue,
            String realized, String unrealized, String totalGainLoss) { }
    public record Snapshot(Totals totals, String previousRealized, String previousUnrealized,
            String fingerprint, int tradeCount) { }
    public record Posting(UUID id, Book book, ClosingRate closingRate, UUID closingRateId,
            Snapshot snapshot, JournalModels.Entry journal) { }
    public record Dispatch(Posting posting, String maker, String issuer, String checker, UUID correlationId) { }

    public static Position position(List<Flow> flows) {
        BigDecimal q=BigDecimal.ZERO, cost=BigDecimal.ZERO, cash=BigDecimal.ZERO;
        for (Flow flow:flows) {
            BigDecimal dq=flow.quantity(), f=flow.functionalCash();
            if (dq==null || f==null || dq.signum()==0 || f.signum()==0 || dq.signum()==f.signum())
                throw new IllegalArgumentException("Each FX flow needs opposite nonzero quantity and cash");
            if (q.signum()==0 || q.signum()==dq.signum()) cost=cost.subtract(f);
            else {
                BigDecimal closed=q.abs().min(dq.abs());
                BigDecimal released=cost.multiply(closed).divide(q.abs(),30,RoundingMode.HALF_EVEN);
                cost=cost.subtract(released);
                BigDecimal opened=dq.abs().subtract(closed);
                if (opened.signum()>0) cost=cost.subtract(f.multiply(opened).divide(dq.abs(),30,RoundingMode.HALF_EVEN));
            }
            q=q.add(dq); cash=cash.add(f);
            if(q.signum()==0) cost=BigDecimal.ZERO;
        }
        return new Position(q,cost,cash);
    }
    public static Totals value(Position p, BigDecimal rate, String functionalCurrency) {
        int scale=Currency.getInstance(functionalCurrency).getDefaultFractionDigits();
        if(rate==null || rate.signum()<=0 || scale<0) throw new IllegalArgumentException("Invalid closing rate/currency");
        BigDecimal market=p.quantity().multiply(rate).setScale(scale,RoundingMode.HALF_EVEN);
        BigDecimal realized=p.cash().add(p.cost()).setScale(scale,RoundingMode.HALF_EVEN);
        BigDecimal total=p.cash().add(market).setScale(scale,RoundingMode.HALF_EVEN);
        return new Totals(text(p.quantity()),text(p.cost()),text(p.cash()),text(market),
                text(realized),text(total.subtract(realized)),text(total));
    }
    public static List<JournalModels.Line> lines(Policy p, Totals t, String previousRealized, String previousUnrealized) {
        var lines=new ArrayList<JournalModels.Line>();
        BigDecimal r=new BigDecimal(t.realized()), u=new BigDecimal(t.unrealized());
        BigDecimal pr=new BigDecimal(previousRealized), pu=new BigDecimal(previousUnrealized);
        add(lines,p.functionalPositionLedger(),r.add(u).subtract(pr).subtract(pu));
        add(lines,p.realizedGainLedger(),pr.max(BigDecimal.ZERO).subtract(r.max(BigDecimal.ZERO)));
        add(lines,p.realizedLossLedger(),r.negate().max(BigDecimal.ZERO).subtract(pr.negate().max(BigDecimal.ZERO)));
        add(lines,p.unrealizedGainLedger(),pu.max(BigDecimal.ZERO).subtract(u.max(BigDecimal.ZERO)));
        add(lines,p.unrealizedLossLedger(),u.negate().max(BigDecimal.ZERO).subtract(pu.negate().max(BigDecimal.ZERO)));
        return List.copyOf(lines);
    }
    private static void add(List<JournalModels.Line> lines, UUID ledger, BigDecimal debit) {
        if(debit.signum()!=0) lines.add(new JournalModels.Line(ledger,debit.signum()>0?JournalModels.Side.DEBIT:JournalModels.Side.CREDIT,text(debit.abs())));
    }
    private static String text(BigDecimal n) { return FxModels.text(n); }
}
