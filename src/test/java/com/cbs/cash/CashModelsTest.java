package com.cbs.cash;

import static org.assertj.core.api.Assertions.*;
import static com.cbs.cash.CashModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CashModelsTest {
 @Test void legacyCashPayloadRemainsCompatible(){
  var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
  var change=new Change(0,Kind.COUNT,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),null,null,null,null,null,null,null,java.time.LocalDate.of(2026,10,7),"legacy",List.of(),"Evidence");
  String payload=mapper.writeValueAsString(change);
  assertThat(payload).doesNotContain("exceptionId","originalId","transit");
  assertThat(mapper.readValue(payload,Change.class)).isEqualTo(change);
 }
 @Test void countsAreExactAndRespectCurrencyPrecision(){
  assertThat(counted("KES",List.of(new Count("0.10",3),new Count("1000",2)))).isEqualByComparingTo("2000.30");
  assertThat(counted("RWF",List.of(new Count("5000",3)))).isEqualByComparingTo("15000");
  assertThat(counted("UGX",List.of(new Count("50000",2)))).isEqualByComparingTo("100000");
  assertThatThrownBy(()->counted("RWF",List.of(new Count("0.5",1)))).hasMessageContaining("Cash custody");
  assertThat(counted("KES",List.of(new Count("9007199254740993.17",1)))).isEqualByComparingTo("9007199254740993.17");
 }
 @Test void duplicatesNegativeCountsAndOverflowAreRejected(){
  assertThatThrownBy(()->counted("KES",List.of(new Count("50",1),new Count("50.00",2)))).isInstanceOf(com.cbs.tenancy.TenantException.class);
  assertThatThrownBy(()->counted("KES",List.of(new Count("50",-1)))).isInstanceOf(com.cbs.tenancy.TenantException.class);
  assertThatThrownBy(()->counted("KES",List.of(new Count("0",1)))).isInstanceOf(com.cbs.tenancy.TenantException.class);
  assertThatThrownBy(()->counted("KES",List.of(new Count("999999999999999999",2)))).isInstanceOf(com.cbs.tenancy.TenantException.class);
  assertThat(counted("KES",List.of())).isZero();
 }
}
