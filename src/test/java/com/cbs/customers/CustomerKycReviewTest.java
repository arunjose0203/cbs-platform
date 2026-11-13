package com.cbs.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.customers.CustomerModels.*;
import com.cbs.tenancy.BankDatabaseProvisioner;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class CustomerKycReviewTest {
    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private BankCustomerStore store;
    private BankDatabaseProvisioner databases;
    private PlatformTransactionManager txManager;
    private CustomerService service;

    private final UUID bankId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final Actor adminActor = new Actor("test-issuer", "admin-user", true, UUID.randomUUID());
    private final TenantModels.Tenant activeBank = new TenantModels.Tenant(
            bankId, "TESTBANK", "Test Bank", "en", TenantModels.Status.ACTIVE, 1L,
            Instant.EPOCH, Instant.EPOCH, List.of(), null);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tenants = mock(TenantRepository.class);
        store = mock(BankCustomerStore.class);
        databases = mock(BankDatabaseProvisioner.class);
        txManager = mock(PlatformTransactionManager.class);
        service = new CustomerService(jdbc, tenants, store, databases, txManager, new com.cbs.privacy.PersonalDataCipher(""));
        when(tenants.get(eq(bankId), anyBoolean())).thenReturn(activeBank);
    }

    @Test
    void riskRatingDeterminesReviewInterval() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        LocalDate reviewDate = LocalDate.now(ZoneOffset.UTC).minusDays(10);

        // HIGH risk: 1 year interval
        KycReviewInput highInput = new KycReviewInput("HIGH", reviewDate, "REV-HIGH-01", "High risk annual review");
        KycReviewSummary highSummary = service.recordReview(adminActor, bankId, customerId, highInput);
        assertThat(highSummary.nextReviewDue()).isEqualTo(reviewDate.plusYears(1));
        assertThat(highSummary.riskRating()).isEqualTo("HIGH");

        // MEDIUM risk: 2 years interval
        KycReviewInput medInput = new KycReviewInput("MEDIUM", reviewDate, "REV-MED-01", "Medium risk biennial review");
        KycReviewSummary medSummary = service.recordReview(adminActor, bankId, customerId, medInput);
        assertThat(medSummary.nextReviewDue()).isEqualTo(reviewDate.plusYears(2));
        assertThat(medSummary.riskRating()).isEqualTo("MEDIUM");

        // LOW risk: 3 years interval
        KycReviewInput lowInput = new KycReviewInput("LOW", reviewDate, "REV-LOW-01", "Low risk triennial review");
        KycReviewSummary lowSummary = service.recordReview(adminActor, bankId, customerId, lowInput);
        assertThat(lowSummary.nextReviewDue()).isEqualTo(reviewDate.plusYears(3));
        assertThat(lowSummary.riskRating()).isEqualTo("LOW");
    }

    @Test
    void futureReviewDateIsRejected() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        KycReviewInput input = new KycReviewInput("HIGH", tomorrow, "REV-01", "Future review");

        assertThatThrownBy(() -> service.recordReview(adminActor, bankId, customerId, input))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("REVIEW_DATE_FUTURE"));
    }

    @Test
    void deceasedCustomerFailsEligibility() {
        Profile deceasedProfile = new Profile(
                "John Doe",
                LocalDate.of(1980, 1, 1),
                "US",
                "US",
                "123 Main St",
                "+1234567890",
                "john@example.com",
                DocumentType.PASSPORT,
                "US",
                "P1234567",
                LocalDate.of(2030, 1, 1),
                "REF-DOC-1",
                State.DECEASED,
                "DECEASED-NOTICE-01"
        );
        Definition deceasedDef = new Definition(customerId, bankId, "C001", 1L, deceasedProfile);

        assertThatThrownBy(() -> CustomerService.checkEligible(deceasedDef))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("CUSTOMER_DECEASED"));
    }

    @Test
    void deceasedCustomerValidationRejectsInitialStateOrMissingReview() {
        when(store.ready(activeBank)).thenReturn(true);

        Profile deceasedNoReview = new Profile(
                "Jane Doe",
                LocalDate.of(1985, 5, 15),
                "US",
                "US",
                "456 Market St",
                "+1234567891",
                "jane@example.com",
                DocumentType.PASSPORT,
                "US",
                "P7654321",
                LocalDate.of(2030, 1, 1),
                "REF-DOC-2",
                State.DECEASED,
                ""
        );

        // Cannot create brand new customer directly in DECEASED state
        Change initialDeceased = new Change(1L, null, deceasedNoReview, "", "Initial creation");
        assertThatThrownBy(() -> service.validate(activeBank, initialDeceased))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("CUSTOMER_INITIAL_STATE"));

        // Transition to DECEASED requires reviewReference
        Change updateDeceasedNoRef = new Change(1L, customerId, deceasedNoReview, "", "Mark deceased");
        assertThatThrownBy(() -> service.validate(activeBank, updateDeceasedNoRef))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("CUSTOMER_DECEASED_REVIEW_REQUIRED"));
    }
}
