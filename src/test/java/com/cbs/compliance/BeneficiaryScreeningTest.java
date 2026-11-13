package com.cbs.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class BeneficiaryScreeningTest {

    private JdbcTemplate jdbc;
    private ScreeningService screening;
    private TenantRepository tenants;
    private PlatformTransactionManager tm;
    private BeneficiaryScreening service;

    private final UUID bankId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final Actor actor = new Actor("http://test-idp", "compliance-officer", true, UUID.randomUUID());

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        screening = mock(ScreeningService.class);
        tenants = mock(TenantRepository.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new BeneficiaryScreening(jdbc, screening, tenants, tm);
    }

    @Test
    void requirePassesWhenNoScreeningMatches() {
        when(screening.matchName(bankId, "John Doe")).thenReturn(List.of());
        service.require(bankId, accountId, customerId, "254712345678", "John Doe", BeneficiaryScreening.Stage.SUBMIT);
    }

    @Test
    void requirePassesWhenMatchWasAlreadyDismissed() {
        var entry = new NameMatcher.Entry("REF-1", true, List.of("John Doe"), null, "HASH-1");
        var match = new NameMatcher.Match(entry, "John Doe", 0.95);
        var nameMatch = new ScreeningService.NameMatch(UUID.randomUUID(), "SANCTIONS_LIST", match);
        when(screening.matchName(bankId, "John Doe")).thenReturn(List.of(nameMatch));

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq("254712345678"), eq("john doe"),
                eq("SANCTIONS_LIST"), eq("REF-1"), eq("HASH-1"))).thenReturn(Boolean.TRUE);

        service.require(bankId, accountId, customerId, "254712345678", "John Doe", BeneficiaryScreening.Stage.SUBMIT);
    }

    @Test
    void requireRefusesAndRecordsHitWhenMatchIsNotDismissed() {
        var entry = new NameMatcher.Entry("REF-1", true, List.of("Target Person"), null, "HASH-1");
        var match = new NameMatcher.Match(entry, "Target Person", 0.92);
        var nameMatch = new ScreeningService.NameMatch(UUID.randomUUID(), "SANCTIONS_LIST", match);
        when(screening.matchName(bankId, "Target Person")).thenReturn(List.of(nameMatch));

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq("254712345678"), eq("target person"),
                eq("SANCTIONS_LIST"), eq("REF-1"), eq("HASH-1"))).thenReturn(Boolean.FALSE);

        assertThatThrownBy(() -> service.require(bankId, accountId, customerId, "254712345678", "Target Person", BeneficiaryScreening.Stage.SUBMIT))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "PAYMENT_BENEFICIARY_SCREENING_MATCH");

        verify(jdbc).update(anyString(), any(UUID.class), eq(bankId), eq("254712345678"), eq("Target Person"),
                eq("target person"), eq(accountId), eq(customerId), eq(nameMatch.listId()), eq("REF-1"),
                eq("SANCTIONS_LIST"), eq("HASH-1"), eq(BigDecimal.valueOf(0.92)), eq("Target Person"), eq("SUBMIT"));
    }
}
