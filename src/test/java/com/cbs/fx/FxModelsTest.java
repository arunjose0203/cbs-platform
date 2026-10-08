package com.cbs.fx;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
class FxModelsTest {
 FxModels.Rate rate(String base,String quote,String bid,String mid,String ask){return new FxModels.Rate(0,base,quote,LocalDate.of(2026,10,8),LocalDate.of(2026,10,8),bid,mid,ask,Instant.parse("2026-10-09T00:00:00Z"),"TEST/RATE","Fictional rates");}
 @Test void usesBankBidForBaseSoldAndAskForBaseBought(){var r=rate("USD","KES","129.25","130","130.75");assertThat(FxModels.calculate(UUID.randomUUID(),r,FxModels.Side.BANK_BUYS_BASE,"100","HALF_EVEN",r.effectiveFrom()).targetAmount()).isEqualTo("12925");assertThat(FxModels.calculate(UUID.randomUUID(),r,FxModels.Side.BANK_SELLS_BASE,"13075","HALF_EVEN",r.effectiveFrom()).targetAmount()).isEqualTo("100");}
 @Test void roundsRwandaAndUgandaToTheirCurrencyPrecision(){for(String currency:List.of("RWF","UGX")){var r=rate("USD",currency,"1000.5","1001","1002");assertThat(FxModels.calculate(UUID.randomUUID(),r,FxModels.Side.BANK_BUYS_BASE,"1","HALF_EVEN",r.effectiveFrom()).targetAmount()).isEqualTo("1000");assertThatThrownBy(()->FxModels.calculate(UUID.randomUUID(),r,FxModels.Side.BANK_SELLS_BASE,"1.5","HALF_EVEN",r.effectiveFrom())).isInstanceOf(IllegalArgumentException.class);}}
 @Test void rejectsCrossedRatesZeroRatesSameCurrencyAndRoundedZero(){for(var r:List.of(rate("USD","KES","131","130","132"),rate("USD","KES","0","1","2"),rate("KES","KES","1","1","1")))assertThatThrownBy(()->FxModels.validateRates(r)).isInstanceOf(IllegalArgumentException.class);var tiny=rate("USD","RWF","0.00000001","0.00000002","0.00000003");assertThatThrownBy(()->FxModels.calculate(UUID.randomUUID(),tiny,FxModels.Side.BANK_BUYS_BASE,"0.01","HALF_EVEN",tiny.effectiveFrom())).isInstanceOf(IllegalArgumentException.class);}
 @Test void doesNotLoseLargeIntegerPrecision(){var r=rate("USD","KES","1","1","1");assertThat(FxModels.calculate(UUID.randomUUID(),r,FxModels.Side.BANK_BUYS_BASE,"9007199254740993.01","HALF_EVEN",r.effectiveFrom()).targetAmount()).isEqualTo("9007199254740993.01");}
}
