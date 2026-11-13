package com.cbs.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.products.ProductModels;
import com.cbs.tenancy.BankDatabaseProvisioner;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DepositLifecycleTest {
    private JdbcTemplate jdbc;
    private BankDatabaseProvisioner databases;
    private AccountClosureService closureService;

    private final UUID bankId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final TenantModels.Tenant activeBank = new TenantModels.Tenant(
            bankId, "TESTBANK", "Test Bank", "en", TenantModels.Status.ACTIVE, 1L,
            Instant.EPOCH, Instant.EPOCH, List.of(), null);

    private final ProductModels.Product testProduct = new ProductModels.Product(
            UUID.randomUUID(), "SAVINGS", "Savings Account", "", ProductModels.Type.SAVINGS,
            "USD", "100.00", "10.00", UUID.randomUUID(), "100", true, 1,
            Instant.EPOCH, Instant.EPOCH);

    private final AccountModels.Definition testAccount = new AccountModels.Definition(
            accountId, bankId, "ACC-001", 1L, customerId, "CUST-001",
            UUID.randomUUID(), "Holding LLC", "Main Account", AccountModels.State.ACTIVE,
            testProduct, Instant.EPOCH);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        databases = mock(BankDatabaseProvisioner.class);
        closureService = new AccountClosureService(jdbc, databases);

        // Default: queries returning Boolean return false unless overridden
        lenient().when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object.class))).thenReturn(false);
        lenient().when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object.class), any(Object.class))).thenReturn(false);
        lenient().when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object.class), any(Object.class), any(Object.class))).thenReturn(false);

        lenient().when(jdbc.query(contains("SELECT ledger_id FROM platform.account_ledger"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
    }

    @Test
    void closedContractsDoNotBlockAccountClosure() {
        when(jdbc.queryForObject(contains("platform.contract WHERE account_id=? AND status<>'CLOSED'"), eq(Boolean.class), eq(accountId)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("platform.payment_order WHERE account_id=?"), eq(Boolean.class), eq(accountId), eq(accountId)))
                .thenReturn(false);

        AccountClosureService.Check check = closureService.check(activeBank, testAccount);
        assertThat(check.blockers()).doesNotContain("ACCOUNT_CLOSURE_CONTRACT_REVIEW");
        assertThat(check.blockers()).doesNotContain("ACCOUNT_CLOSURE_PAYMENT_REVIEW");
    }

    @Test
    void activeContractsBlockAccountClosure() {
        when(jdbc.queryForObject(contains("platform.contract WHERE account_id=? AND status<>'CLOSED'"), eq(Boolean.class), eq(accountId)))
                .thenReturn(true);

        AccountClosureService.Check check = closureService.check(activeBank, testAccount);
        assertThat(check.blockers()).contains("ACCOUNT_CLOSURE_CONTRACT_REVIEW");
    }

    @Test
    void pendingPaymentsBlockAccountClosure() {
        when(jdbc.queryForObject(contains("platform.payment_order WHERE account_id=?"), eq(Boolean.class), eq(accountId), eq(accountId)))
                .thenReturn(true);

        AccountClosureService.Check check = closureService.check(activeBank, testAccount);
        assertThat(check.blockers()).contains("ACCOUNT_CLOSURE_PAYMENT_REVIEW");
    }

    @Test
    void closureSettlementReversalUnblocksPendingClosureState() {
        // Without reversal: settling exists and no reversal -> throws ACCOUNT_PENDING_CLOSURE
        when(jdbc.queryForObject(contains("CLOSURE_SETTLEMENT"), eq(Boolean.class), eq(accountId), eq(accountId)))
                .thenReturn(true);

        assertThatThrownBy(() -> AccountClosureService.requireNotSettling(jdbc, accountId))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("ACCOUNT_PENDING_CLOSURE"));

        // With reversal completed: query returns false (NOT EXISTS unblocks) -> does not throw
        when(jdbc.queryForObject(contains("CLOSURE_SETTLEMENT"), eq(Boolean.class), eq(accountId), eq(accountId)))
                .thenReturn(false);

        AccountClosureService.requireNotSettling(jdbc, accountId);
    }
}
