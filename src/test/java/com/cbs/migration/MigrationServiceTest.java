package com.cbs.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.privacy.PersonalDataCipher;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** P-10: the data-migration service, exercised through its own methods rather than re-implemented in the test. */
class MigrationServiceTest {
    private static final String FIELD_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    private final UUID bank = UUID.randomUUID();
    private final Actor admin = new Actor("issuer", "maker", true, UUID.randomUUID());
    private JdbcTemplate jdbc;
    private MigrationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new MigrationService(jdbc, mock(TenantRepository.class), null, null, manager, new PersonalDataCipher(FIELD_KEY), null);
    }

    private static MigrationModels.CustomerRecord customer(String legacy, String nationalId, String name) {
        return new MigrationModels.CustomerRecord(legacy, nationalId, name, "+254712345678", "a@example.com", LocalDate.of(1985, 5, 20), "KE", "ID/1");
    }

    @Test
    void validationFlagsDuplicatesAndBlanksWithoutRepeatingTheIdentityNumber() {
        var report = service.validateCustomers(bank, List.of(customer("L1", "12345678", "Alice"), customer("L2", "1234-5678", "Duplicate"),
                customer("L3", "", "Missing"), customer("L4", "87654321", "Bob")));
        assertThat(report.validCount()).isEqualTo(2);
        assertThat(report.findings()).extracting(MigrationModels.ValidationFinding::code).containsExactlyInAnyOrder("DUPLICATE_IN_FILE", "NATIONAL_ID_REQUIRED");
        assertThat(report.findings()).allSatisfy(f -> assertThat(f.message()).doesNotContain("1234"));
    }

    @Test
    void stagedCopiesHoldTheIdentityNumberSealed() {
        service.stageCustomers(admin, bank, List.of(customer("L1", "29871634", "Alice")));
        var args = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO platform.migration_staged_item"), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        String payload = (String) args.getAllValues().get(4);
        assertThat(payload).contains("\"nationalId\":\"pd1:").doesNotContain("29871634");
    }

    @Test
    void batchCommitIsRefusedUntilItGoesThroughCustomerApproval() {
        assertThatThrownBy(() -> service.commitCustomers(admin, bank, UUID.randomUUID()))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("MIGRATION_COMMIT_UNAVAILABLE"));
        verify(jdbc, never()).update(contains("customer_registry"), any(Object[].class));
    }

    @Test
    void reconciliationTakesExactlyOneNonNegativeCurrencyTotal() {
        for (Map<String, BigDecimal> input : List.of(Map.<String, BigDecimal>of(),
                Map.of("KES", new BigDecimal("1"), "USD", new BigDecimal("1"))))
            assertThatThrownBy(() -> service.reconcileTrialBalance(admin, bank, input))
                    .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("RECONCILIATION_ONE_CURRENCY"));
        for (Map<String, BigDecimal> input : List.of(Map.of("KES", new BigDecimal("-1")), Map.of("kes", BigDecimal.ONE)))
            assertThatThrownBy(() -> service.reconcileTrialBalance(admin, bank, input))
                    .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("RECONCILIATION_INVALID"));
    }
}
