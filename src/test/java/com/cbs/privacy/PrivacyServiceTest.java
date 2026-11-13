package com.cbs.privacy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.customers.CustomerService;
import com.cbs.privacy.PrivacyModels.*;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

class PrivacyServiceTest {
    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private CustomerService customers;
    private PlatformTransactionManager txManager;
    private PrivacyService service;

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
        customers = mock(CustomerService.class);
        txManager = mock(PlatformTransactionManager.class);
        service = new PrivacyService(jdbc, tenants, customers, txManager, 7);
        when(tenants.get(eq(bankId), anyBoolean())).thenReturn(activeBank);
    }

    @Test
    void retentionMatrixContainsStatutoryRules() {
        var rules = service.matrix();
        assertThat(rules).isNotEmpty();
        assertThat(rules).anySatisfy(r -> {
            assertThat(r.category()).contains("KYC");
            assertThat(r.years()).isEqualTo(7);
        });
        assertThat(rules).anySatisfy(r -> {
            assertThat(r.category()).contains("Transactions");
            assertThat(r.years()).isEqualTo(7);
        });
    }

    @Test
    void legalHoldValidationRejectsInvalidTransitions() {
        when(jdbc.queryForList(contains("SELECT customer_number FROM platform.customer_registry"), eq(String.class), eq(bankId), eq(customerId)))
                .thenReturn(List.of("C001"));

        // Placing hold when already held
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM platform.legal_hold"), eq(Boolean.class), eq(bankId), eq(customerId)))
                .thenReturn(true);
        Hold placeHold = new Hold(1L, customerId, true, "HOLD-01", "Court inquiry");
        assertThatThrownBy(() -> service.validate(activeBank, placeHold))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("LEGAL_HOLD_ALREADY_ACTIVE"));

        // Releasing hold when none is active
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM platform.legal_hold"), eq(Boolean.class), eq(bankId), eq(customerId)))
                .thenReturn(false);
        Hold releaseHold = new Hold(1L, customerId, false, "HOLD-01", "Court inquiry concluded");
        assertThatThrownBy(() -> service.validate(activeBank, releaseHold))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("LEGAL_HOLD_NOT_ACTIVE"));
    }

    @Test
    void applyLegalHoldUpdatesDatabaseAndAudits() {
        when(jdbc.queryForList(contains("SELECT customer_number FROM platform.customer_registry"), eq(String.class), eq(bankId), eq(customerId)))
                .thenReturn(List.of("C001"));
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM platform.legal_hold"), eq(Boolean.class), eq(bankId), eq(customerId)))
                .thenReturn(false);

        UUID approvalId = UUID.randomUUID();
        Hold hold = new Hold(1L, customerId, true, "HOLD-01", "Court subpoena 2026");
        service.apply(adminActor, bankId, hold, approvalId);

        verify(jdbc).update(contains("INSERT INTO platform.legal_hold"), eq(bankId), eq(customerId), eq(true), eq("HOLD-01"), eq("Court subpoena 2026"), eq(approvalId));
        verify(tenants).audit(eq(adminActor), eq(bankId), eq("LEGAL_HOLD_PLACED"), eq(2L), any(), any(), contains("Approved request"), any(), any(), any());
    }

    @Test
    void registerSubjectRequestRejectsFutureDate() {
        when(jdbc.queryForList(contains("SELECT customer_number FROM platform.customer_registry"), eq(String.class), eq(bankId), eq(customerId)))
                .thenReturn(List.of("C001"));

        UUID key = UUID.randomUUID();
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        SubjectRequest futureReq = new SubjectRequest(customerId, RequestType.ACCESS, tomorrow, "REQ-01", "Customer access request");

        assertThatThrownBy(() -> service.register(adminActor, bankId, key, futureReq))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("PRIVACY_REQUEST_DATE_INVALID"));
    }

    @Test
    void erasureAssessmentIdentifiesActiveHoldAndOpenAccounts() {
        when(jdbc.queryForList(contains("SELECT customer_number FROM platform.customer_registry"), eq(String.class), eq(bankId), eq(customerId)))
                .thenReturn(List.of("C001"));

        // Mock assessment query returning held=true, open=true, cased=false
        when(jdbc.query(contains("SELECT c.id,c.customer_number"), any(RowMapper.class), eq(bankId), eq(customerId)))
                .thenAnswer(inv -> {
                    RowMapper<ErasureAssessment> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getBoolean("open")).thenReturn(true);
                    when(rs.getBoolean("held")).thenReturn(true);
                    when(rs.getBoolean("cased")).thenReturn(false);
                    when(rs.getTimestamp("ended")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 1));
                });

        ErasureAssessment assessment = service.assess(adminActor, bankId, customerId);
        assertThat(assessment.customerId()).isEqualTo(customerId);
        assertThat(assessment.blockers()).contains("LEGAL_HOLD", "RELATIONSHIP_OPEN");
    }

    @Test
    void closeAccessRequestRequiresPriorReportExport() {
        UUID reqId = UUID.randomUUID();
        // Mock request query: type=ACCESS, status=OPEN, exported=false
        when(jdbc.queryForList(contains("SELECT request_type,status,customer_id,report_exported_at IS NOT NULL AS exported"), eq(bankId), eq(reqId)))
                .thenReturn(List.of(Map.of("request_type", "ACCESS", "status", "OPEN", "customer_id", customerId, "exported", false)));

        Close closeAttempt = new Close(RequestStatus.COMPLETED, "FULFILLED", "Report delivered");
        assertThatThrownBy(() -> service.close(adminActor, bankId, reqId, closeAttempt))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("PRIVACY_REPORT_NOT_EXPORTED"));
    }

    @Test
    void closeErasureRequestAsCompletedIsProhibited() {
        UUID reqId = UUID.randomUUID();
        when(jdbc.queryForList(contains("SELECT request_type,status,customer_id,report_exported_at IS NOT NULL AS exported"), eq(bankId), eq(reqId)))
                .thenReturn(List.of(Map.of("request_type", "ERASURE", "status", "OPEN", "customer_id", customerId, "exported", false)));

        Close closeAttempt = new Close(RequestStatus.COMPLETED, "ERASED", "Attempt to mark erased");
        assertThatThrownBy(() -> service.close(adminActor, bankId, reqId, closeAttempt))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("PRIVACY_ERASURE_NOT_AVAILABLE"));
    }
}
