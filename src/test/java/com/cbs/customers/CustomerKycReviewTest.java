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

    private KycReviewChange review(String rating, LocalDate date) {
        return CustomerService.reviewChange(customerId, new KycReviewInput(1L, rating, date, " REV-" + rating + " ", "Periodic review"));
    }

    @Test
    void riskRatingDeterminesReviewInterval() {
        LocalDate reviewDate = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        assertThat(service.reviewValues(review("HIGH", reviewDate))).containsEntry("nextReviewDue", reviewDate.plusYears(1).toString());
        assertThat(service.reviewValues(review("MEDIUM", reviewDate))).containsEntry("nextReviewDue", reviewDate.plusYears(2).toString());
        assertThat(service.reviewValues(review("LOW", reviewDate))).containsEntry("nextReviewDue", reviewDate.plusYears(3).toString())
                .containsEntry("reviewReference", "REV-LOW");
    }

    @Test
    void futureOrBackdatedReviewDatesAreRejected() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThatThrownBy(() -> service.validateReview(activeBank, review("HIGH", today.plusDays(1))))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("REVIEW_DATE_FUTURE"));
        // P-10: a backdated review would shorten the next due date and hide an overdue review.
        assertThatThrownBy(() -> service.validateReview(activeBank, review("LOW", today.minusDays(31))))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("REVIEW_DATE_TOO_OLD"));
        service.validateReview(activeBank, review("LOW", today.minusDays(30)));
    }

    @Test
    void aReviewNeedsTheCurrentBankVersion() {
        var stale = new KycReviewChange(0L, customerId, "LOW", LocalDate.now(ZoneOffset.UTC), "REV", null);
        assertThatThrownBy(() -> service.validateReview(activeBank, stale))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("STALE_VERSION"));
    }

    @Test
    void theApprovedReviewRecordsTheMakerAsReviewer() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        var checker = new Actor("test-issuer", "checker-user", true, UUID.randomUUID());
        UUID approval = UUID.randomUUID();
        service.applyReview(checker, bankId, review("HIGH", LocalDate.now(ZoneOffset.UTC)), "maker-user", approval);
        verify(jdbc).update(startsWith("INSERT INTO platform.customer_kyc_review"), any(), eq(bankId), eq(customerId), eq("HIGH"), any(), any(), eq("maker-user"), eq("REV-HIGH"), eq("Periodic review"));
        verify(tenants).change(eq(activeBank), any(), any(), any());
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
