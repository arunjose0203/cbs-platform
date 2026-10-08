package com.cbs.contracts;
import static org.assertj.core.api.Assertions.*;
import static com.cbs.contracts.ContractModels.*;
import java.time.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ContractModelsTest {
    @Test void accrualUsesActualDaysAndCarriesFractionalCurrencyUnits(){
        var start=LocalDate.of(2026,1,1);var p=interest("1000","5",365,start,start.plusDays(1),"0",2);
        assertThat(p.interest()).isEqualTo("0.14");assertThat(decimal(p.carry())).isNegative();
        BigDecimal total=decimal(p.interest());String carry=p.carry();
        for(int n=1;n<365;n++){var next=interest("1000","5",365,start.plusDays(n),start.plusDays(n+1),carry,2);carry=next.carry();total=total.add(decimal(next.interest()));}
        assertThat(total).isEqualByComparingTo("50");
    }
    @Test void monthlySchedulePreservesPrincipalAndUsesCalendarMonthEnds(){
        var start=LocalDate.of(2026,1,31);var terms=new Open(0,Type.LOAN,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,"100","12","0",365,start,start.plusMonths(3),3,"TEST","Test");
        var schedule=schedule(terms,2);assertThat(schedule).extracting(Instalment::dueDate).containsExactly(LocalDate.of(2026,2,28),LocalDate.of(2026,3,31),LocalDate.of(2026,4,30));
        assertThat(schedule).extracting(Instalment::principal).containsExactly("33.33","33.33","33.34");assertThat(schedule.stream().map(x->decimal(x.principal())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("100");
        assertThat(decimal(schedule.getFirst().projectedInterest())).isGreaterThan(decimal(schedule.getLast().projectedInterest()));
    }
    @Test void largeAmountsStayExactAndZeroDecimalCurrenciesRoundAtTheBoundary(){
        var start=LocalDate.of(2026,1,1);assertThat(interest("9007199254740993","1",360,start,start.plusDays(360),"0",2).interest()).isEqualTo("90071992547409.93");
        assertThat(interest("1000","36.5",365,start,start.plusDays(1),"0",0).interest()).isEqualTo("1");
        assertThatThrownBy(()->interest("1","1",364,start,start.plusDays(1),"0",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void newCorrectionMetadataDoesNotChangeLegacyReceiptSerialization(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var date=LocalDate.of(2026,10,8);var position=new Position("100","0","0",date,false);
        var legacy=new Posting(UUID.randomUUID(),null,Kind.ACCRUE,date,position,position,"0","0",null,"Legacy receipt");
        var payload=json.writeValueAsString(legacy);assertThat(payload).doesNotContain("originalEventId","servicing");
        assertThat(json.readValue(payload,Posting.class)).isEqualTo(legacy);
    }
}
