package com.cbs.contracts;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** P-10 (S-12): maturity proceeds may only go to an account of the same depositor, in the deposit's currency. */
class StandingInstructionGuardTest {
    private final UUID bank = UUID.randomUUID(), contract = UUID.randomUUID(), deposit = UUID.randomUUID(), depositor = UUID.randomUUID();
    private final TenantModels.Tenant active = new TenantModels.Tenant(bank, "B", "Bank", "en", TenantModels.Status.ACTIVE, 7L,
            Instant.EPOCH, Instant.EPOCH, List.of(), null);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TermStandingInstructionService service;

    StandingInstructionGuardTest() {
        var contracts = mock(ContractService.class);
        var definition = mock(ContractModels.Definition.class, RETURNS_DEEP_STUBS);
        when(definition.terms().type()).thenReturn(ContractModels.Type.TERM_DEPOSIT);
        when(definition.account().id()).thenReturn(deposit);
        when(contracts.definition(bank, contract)).thenReturn(definition);
        service = new TermStandingInstructionService(jdbc, mock(com.cbs.tenancy.TenantRepository.class), contracts, mock(PlatformTransactionManager.class));
        when(jdbc.queryForList(contains("account_registry"), eq(bank), eq(deposit))).thenReturn(List.of(Map.of("customer_id", depositor, "currency", "KES")));
    }

    private TermStandingInstructionModels.InstructionRequest redeemTo(UUID target) {
        return new TermStandingInstructionModels.InstructionRequest(7L, contract,
                TermStandingInstructionModels.InstructionType.TRANSFER_TO_ACCOUNT_AT_MATURITY, target, null, null, "Mandate");
    }

    private void account(UUID id, UUID customer, String currency) {
        when(jdbc.queryForList(contains("account_registry"), eq(bank), eq(id))).thenReturn(List.of(Map.of("customer_id", customer, "currency", currency)));
    }

    @Test
    void proceedsGoOnlyToTheDepositorsOwnAccountInTheSameCurrency() {
        UUID own = UUID.randomUUID(), stranger = UUID.randomUUID(), dollars = UUID.randomUUID();
        account(own, depositor, "KES");
        account(stranger, UUID.randomUUID(), "KES");
        account(dollars, depositor, "USD");
        assertThatCode(() -> service.validate(active, redeemTo(own))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validate(active, redeemTo(stranger)))
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((TenantException) e).code()).isEqualTo("TARGET_ACCOUNT_NOT_DEPOSITOR"));
        assertThatThrownBy(() -> service.validate(active, redeemTo(dollars)))
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((TenantException) e).code()).isEqualTo("TARGET_ACCOUNT_CURRENCY"));
        assertThatThrownBy(() -> service.validate(active, redeemTo(null)))
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((TenantException) e).code()).isEqualTo("TARGET_ACCOUNT_REQUIRED"));
    }

    @Test
    void renewalsAreBoundedAndNeedTheCurrentBankVersion() {
        var renew = TermStandingInstructionModels.InstructionType.AUTOMATIC_RENEWAL_CAPITALIZE;
        for (var bad : List.of(new TermStandingInstructionModels.InstructionRequest(7L, contract, renew, null, 121, null, "Too long"),
                new TermStandingInstructionModels.InstructionRequest(7L, contract, renew, UUID.randomUUID(), 12, null, "Has target"),
                new TermStandingInstructionModels.InstructionRequest(7L, contract, renew, null, 12, "100.5", "Rate"),
                new TermStandingInstructionModels.InstructionRequest(6L, contract, renew, null, 12, null, "Stale")))
            assertThatThrownBy(() -> service.validate(active, bad)).isInstanceOf(TenantException.class);
        assertThatCode(() -> service.validate(active, new TermStandingInstructionModels.InstructionRequest(7L, contract, renew, null, 12, "9.5", "OK")))
                .doesNotThrowAnyException();
    }
}
