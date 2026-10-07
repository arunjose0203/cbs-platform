package com.cbs.cash;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.cbs.tenancy.TenantException;
class InventoryCapacityTest {
 @Test void capacityIsCheckedBeforeApprovalIncludingReversalRestoration(){
  BankCashStore.requireQuantity(999999999L,1);BankCashStore.requireQuantity(1000000000L,-1000000000L);
  assertThatThrownBy(()->BankCashStore.requireQuantity(1000000000L,1)).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo("CASH_DENOMINATION_CAPACITY"));
  assertThatThrownBy(()->BankCashStore.requireQuantity(0,-1)).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo("CASH_DENOMINATION_INSUFFICIENT"));
  assertThatThrownBy(()->BankCashStore.requireQuantity(Long.MAX_VALUE,1)).isInstanceOfSatisfying(TenantException.class,e->assertThat(e.code()).isEqualTo("CASH_DENOMINATION_CAPACITY"));
 }
}
