package com.cbs.servicing;
import static org.assertj.core.api.Assertions.*;
import static com.cbs.servicing.ServicingModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServicingModelsTest {
 private Policy policy(String fee,String interestTax,String feeTax){return new Policy(0,UUID.randomUUID(),1,UUID.randomUUID(),LocalDate.of(2026,10,7),"36.5",365,fee,interestTax,feeTax,"HALF_EVEN",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Fictional calculation test");}
 @Test void interestWithholdingAndFeeTaxesRemainExact(){
  var c=calculate(policy("0.5","10","20"),"KES",new BigDecimal("1000"),BigDecimal.ZERO,1);
  assertThat(c.grossInterest()).isEqualTo("1");assertThat(c.interestTax()).isEqualTo("0.1");assertThat(c.feeTax()).isEqualTo("0.1");assertThat(c.netChange()).isEqualTo("0.3");assertThat(c.carryAfter()).isEqualTo("0");
  assertThat(calculate(policy("0.5","10","20"),"KES",new BigDecimal("1000"),BigDecimal.ZERO,3).netChange()).isEqualTo("2.1");
 }
 @Test void zeroDecimalCurrenciesCarryFractionalInterestInsteadOfLosingIt(){
  for(String currency:new String[]{"RWF","UGX"}){
   var carry=BigDecimal.ZERO;var paid=BigDecimal.ZERO;
   for(int i=0;i<1000;i++){var c=calculate(policy("0","0","0"),currency,BigDecimal.ONE,carry,1);carry=new BigDecimal(c.carryAfter());paid=paid.add(new BigDecimal(c.grossInterest()));}
   assertThat(paid).isEqualByComparingTo("1");assertThat(carry).isZero();
  }
 }
 @Test void rejectsFractionalFeesForWholeUnitCurrenciesAndInvalidPeriods(){
  assertThatThrownBy(()->calculate(policy("0.1","0","0"),"UGX",BigDecimal.ONE,BigDecimal.ZERO,1)).isInstanceOf(ArithmeticException.class);
  assertThatThrownBy(()->calculate(policy("0","0","0"),"KES",BigDecimal.ONE,BigDecimal.ZERO,0)).isInstanceOf(IllegalArgumentException.class);
 }
}
