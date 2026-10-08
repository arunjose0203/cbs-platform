package com.cbs.credit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
class CreditModelsTest {
 private CreditModels.Application app(String principal,String income,String expenses,String debt,List<CreditModels.Security> securities){return new CreditModels.Application(0,UUID.randomUUID(),"APP/1","Equipment",principal,"0",365,2,income,expenses,debt,"100",List.of(new CreditModels.Document("D","a".repeat(64),LocalDate.of(2027,1,1))),securities,"Evidence");}
 @Test void computesAffordabilityWithoutBinaryRounding(){var a=CreditModels.assess(app("100","200","70","20",List.of()),LocalDate.of(2026,10,8),2);assertThat(new java.math.BigDecimal(a.peakMonthlyPayment())).isEqualByComparingTo("50");assertThat(new java.math.BigDecimal(a.debtServicePercent())).isEqualByComparingTo("35");assertThat(new java.math.BigDecimal(a.monthlySurplus())).isEqualByComparingTo("60");}
 @Test void guaranteesDoNotInflateCollateralCoverage(){var doc=new CreditModels.Document("S","b".repeat(64),LocalDate.of(2027,1,1));var a=CreditModels.assess(app("100","200","0","0",List.of(new CreditModels.Security(CreditModels.SecurityType.COLLATERAL,"C","Owner","40",LocalDate.of(2026,10,8),doc),new CreditModels.Security(CreditModels.SecurityType.GUARANTEE,"G","Guarantor","1000",LocalDate.of(2026,10,8),doc))),LocalDate.of(2026,10,8),2);assertThat(new java.math.BigDecimal(a.collateralPercent())).isEqualByComparingTo("40");assertThat(a.guaranteeValue()).isEqualTo("1000");}
 @Test void insufficientIncomeRemainsNegativeAndZeroIncomeIsRejected(){assertThat(new java.math.BigDecimal(CreditModels.assess(app("100","20","10","10",List.of()),LocalDate.of(2026,10,8),0).monthlySurplus())).isNegative();assertThatThrownBy(()->CreditModels.assess(app("100","0","0","0",List.of()),LocalDate.of(2026,10,8),0)).isInstanceOf(IllegalArgumentException.class);}
 @Test void largeAmountsRemainExact(){var a=CreditModels.assess(app("9007199254740993.18","99999999999999999","0","0",List.of()),LocalDate.of(2026,10,8),2);assertThat(a.peakMonthlyPayment()).isEqualTo("4503599627370496.59");}
}
