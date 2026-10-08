package com.cbs.fx;

import static com.cbs.fx.FxValuationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class FxValuationModelsTest {
    private Flow flow(String q,String cash){return new Flow(new BigDecimal(q),new BigDecimal(cash));}
    @Test void longRealizationDoesNotCountPriorUnrealizedGainTwice() {
        var first=value(position(List.of(flow("1","-129"))),new BigDecimal("130"),"KES");
        assertEquals("0",first.realized());assertEquals("1",first.unrealized());assertEquals("130",first.marketValue());
        var second=value(position(List.of(flow("1","-129"),flow("-1","131"))),new BigDecimal("130"),"KES");
        assertEquals("2",second.realized());assertEquals("0",second.unrealized());
        var p=policy();var entries=lines(p,second,"0","1");
        assertEquals(3,entries.size());assertEquals("1",entries.getFirst().amount());
        assertEquals("2",entries.get(1).amount());assertEquals("1",entries.get(2).amount());
        balanced(entries);
    }
    @Test void partialSaleUsesWeightedAverageAndCrossingZeroOpensShortAtNewPrice() {
        var p=position(List.of(flow("2","-200"),flow("2","-240"),flow("-1","120")));
        assertEquals(0,p.cost().compareTo(new BigDecimal("330")));
        var t=value(p,new BigDecimal("115"),"KES");assertEquals("10",t.realized());assertEquals("15",t.unrealized());
        p=position(List.of(flow("2","-200"),flow("-3","360")));
        assertEquals(0,p.quantity().compareTo(new BigDecimal("-1")));assertEquals(0,p.cost().compareTo(new BigDecimal("-120")));
        t=value(p,new BigDecimal("125"),"KES");assertEquals("40",t.realized());assertEquals("-5",t.unrealized());
    }
    @Test void shortPositionAndCoverRecognizeLoss() {
        var t=value(position(List.of(flow("-2","260"))),new BigDecimal("131"),"KES");assertEquals("-2",t.unrealized());
        t=value(position(List.of(flow("-2","260"),flow("2","-264"))),new BigDecimal("131"),"KES");assertEquals("-4",t.realized());assertEquals("0",t.unrealized());
        balanced(lines(policy(),t,"0","-2"));
    }
    @Test void zeroMinorUnitCurrenciesAndFractionalCostsRemainBalanced() {
        var p=position(List.of(flow("3","-100"),flow("-1","34")));
        for(String currency:List.of("RWF","UGX")) {
            var t=value(p,new BigDecimal("34.6"),currency);assertEquals("69",t.marketValue());assertEquals("3",t.totalGainLoss());
            assertEquals(0,new BigDecimal(t.realized()).add(new BigDecimal(t.unrealized())).compareTo(new BigDecimal(t.totalGainLoss())));
            balanced(lines(policy(),t,"-1","4"));
        }
    }
    @Test void exactReversalRestoresEmptyBookAndLargeValuesNeverUseFloatingPoint() {
        var t=value(position(List.of(flow("0.01","-1.31"),flow("-0.01","1.31"))),BigDecimal.ONE,"KES");assertEquals("0",t.totalGainLoss());
        var huge=value(position(List.of(flow("999999999999999999","-999999999999999999"))),new BigDecimal("1.01"),"KES");assertEquals("9999999999999999.99",huge.unrealized());
    }
    @Test void gainLossSignSwitchReversesPreviousAccountAndUnchangedValuationNeedsNoJournal() {
        var t=value(position(List.of(flow("1","-100"))),new BigDecimal("98"),"KES");balanced(lines(policy(),t,"0","3"));
        assertTrue(lines(policy(),t,"0","-2").isEmpty());
        assertThrows(IllegalArgumentException.class,()->position(List.of(flow("1","100"))));
        assertThrows(IllegalArgumentException.class,()->value(position(List.of()),BigDecimal.ZERO,"KES"));
    }
    private Policy policy(){return new Policy(0,UUID.randomUUID(),"USD",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),CostBasis.WEIGHTED_AVERAGE,"1000","1000","POLICY-1","Test policy");}
    private void balanced(List<com.cbs.accounting.JournalModels.Line> entries){BigDecimal net=BigDecimal.ZERO;for(var l:entries)net=net.add(new BigDecimal(l.amount()).multiply(BigDecimal.valueOf(l.side()==com.cbs.accounting.JournalModels.Side.DEBIT?1:-1)));assertEquals(0,net.signum());}
}
