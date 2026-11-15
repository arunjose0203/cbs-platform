package com.cbs.complaints;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.access.AccessService;
import com.cbs.access.BankDuties;
import com.cbs.complaints.ComplaintModels.*;
import com.cbs.privacy.PersonalDataCipher;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Rules that need no database: configuration bounds and the resolution checks. The full flow is in the integration suite. */
class ComplaintServiceTest {
    private JdbcTemplate jdbc; private TenantRepository tenants; private ComplaintService service;
    private final UUID bankId = UUID.randomUUID(), complaintId = UUID.randomUUID(), customerId = UUID.randomUUID();
    private final Actor checker = new Actor("test-issuer", "checker", true, UUID.randomUUID());
    private final TenantModels.Tenant bank = new TenantModels.Tenant(bankId, "TESTBANK", "Test Bank", "en", TenantModels.Status.ACTIVE, 4L,
            Instant.EPOCH, Instant.EPOCH, List.of(), null);
    private final PersonalDataCipher cipher = new PersonalDataCipher(PersonalDataCipher.DEVELOPMENT_KEY);

    private ComplaintService service(int acknowledgeDays, int resolveDays) {
        return new ComplaintService(jdbc, tenants, mock(AccessService.class), mock(BankDuties.class), cipher, mock(PlatformTransactionManager.class), acknowledgeDays, resolveDays);
    }
    private void complaint(String status, long version) {
        var row = new HashMap<String, Object>();
        row.put("status", status); row.put("version", version); row.put("customer_id", customerId); row.put("reference", "CMP-2026-000001");
        when(jdbc.queryForList(contains("FROM platform.complaint WHERE tenant_id=? AND id=? FOR UPDATE"), eq(bankId), eq(complaintId))).thenReturn(List.of(row));
    }
    private Resolution resolution(long bankVersion, long caseVersion, Outcome outcome, Redress redress, String amount, String currency) {
        return new Resolution(bankVersion, complaintId, caseVersion, outcome, redress, amount, currency, "DUPLICATE_FEE", "We will refund the second charge", "Fee posted twice");
    }
    private void refused(Resolution r, String code) {
        assertThatThrownBy(() -> service.validate(bank, r)).isInstanceOfSatisfying(TenantException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class); tenants = mock(TenantRepository.class); service = service(2, 14);
        when(tenants.get(eq(bankId), anyBoolean())).thenReturn(bank);
        when(jdbc.queryForList(contains("business_timezone"), eq(String.class), eq(bankId))).thenReturn(List.of("Africa/Nairobi"));
    }

    @Test void deadlinesMustBeSensible() {
        assertThatThrownBy(() -> service(-1, 14)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(2, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(2, 181)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(10, 5)).isInstanceOf(IllegalStateException.class).hasMessageContaining("acknowledgement");
        assertThatCode(() -> service(0, 1)).doesNotThrowAnyException();
    }

    @Test void outcomeAndRedressMustAgree() {
        complaint("OPEN", 3);
        refused(resolution(4, 3, Outcome.UPHELD, Redress.NONE, null, null), "COMPLAINT_RESOLUTION_INVALID");
        refused(resolution(4, 3, Outcome.PARTLY_UPHELD, Redress.NONE, null, null), "COMPLAINT_RESOLUTION_INVALID");
        refused(resolution(4, 3, Outcome.NOT_UPHELD, Redress.REFUND, "10", "KES"), "COMPLAINT_RESOLUTION_INVALID");
        refused(resolution(4, 3, Outcome.NOT_UPHELD, Redress.APOLOGY, null, null), "COMPLAINT_RESOLUTION_INVALID");
        refused(resolution(4, 3, Outcome.WITHDRAWN, Redress.EXPLANATION, null, null), "COMPLAINT_RESOLUTION_INVALID");
        for (var valid : List.of(resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, null, null), resolution(4, 3, Outcome.PARTLY_UPHELD, Redress.COMPENSATION, "20.5", "KES"),
                resolution(4, 3, Outcome.NOT_UPHELD, Redress.EXPLANATION, null, null), resolution(4, 3, Outcome.NOT_UPHELD, Redress.NONE, null, null),
                resolution(4, 3, Outcome.WITHDRAWN, Redress.NONE, null, null)))
            assertThatCode(() -> service.validate(bank, valid)).doesNotThrowAnyException();
    }

    @Test void onlyMonetaryRedressCarriesAPositiveAmountWithItsCurrency() {
        complaint("OPEN", 3);
        refused(resolution(4, 3, Outcome.UPHELD, Redress.REFUND, null, null), "COMPLAINT_REDRESS_INVALID");
        refused(resolution(4, 3, Outcome.UPHELD, Redress.REFUND, "10", null), "COMPLAINT_REDRESS_INVALID");
        refused(resolution(4, 3, Outcome.UPHELD, Redress.REFUND, "0", "KES"), "COMPLAINT_REDRESS_INVALID");
        refused(resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, "10", "KES"), "COMPLAINT_REDRESS_INVALID");
    }

    @Test void resolutionNeedsTheCurrentBankAndComplaintVersionsAndAnOpenComplaint() {
        complaint("OPEN", 3);
        refused(resolution(3, 3, Outcome.UPHELD, Redress.APOLOGY, null, null), "STALE_VERSION");
        refused(resolution(4, 2, Outcome.UPHELD, Redress.APOLOGY, null, null), "COMPLAINT_STALE");
        complaint("RESOLVED", 3);
        refused(resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, null, null), "COMPLAINT_NOT_OPEN");
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(bankId), eq(complaintId))).thenReturn(List.of());
        refused(resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, null, null), "COMPLAINT_NOT_FOUND");
        var suspended = new TenantModels.Tenant(bankId, "TESTBANK", "Test Bank", "en", TenantModels.Status.SUSPENDED, 4L, Instant.EPOCH, Instant.EPOCH, List.of(), null);
        assertThatThrownBy(() -> service.validate(suspended, resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, null, null)))
                .isInstanceOfSatisfying(TenantException.class, e -> assertThat(e.code()).isEqualTo("COMPLAINT_BANK_NOT_ACTIVE"));
    }

    @Test void approvalClosesTheComplaintSealsTheResponseAndAdvancesTheBankVersion() {
        complaint("OPEN", 3);
        UUID approval = UUID.randomUUID();
        service.apply(checker, bankId, resolution(4, 3, Outcome.UPHELD, Redress.REFUND, "150.50", "KES"), "maker", approval);
        verify(jdbc).update(contains("UPDATE platform.complaint SET status='RESOLVED'"), eq("UPHELD"), eq("REFUND"), eq(new java.math.BigDecimal("150.50")), eq("KES"),
                eq("DUPLICATE_FEE"), any(java.time.LocalDate.class), eq(approval), eq(complaintId));
        // The response is stored sealed, and the event carries the maker so the history shows both people.
        verify(jdbc).update(contains("INSERT INTO platform.complaint_event"), any(UUID.class), eq(complaintId), eq(bankId), eq("RESOLVED"), eq("test-issuer"), eq("checker"),
                argThat((String note) -> note.startsWith("pd1:") && !note.contains("refund")), argThat((String details) -> details.contains("\"proposedBy\":\"maker\"") && details.contains(approval.toString())), eq(4L));
        verify(tenants).change(eq(bank), eq(TenantModels.Status.ACTIVE), eq("Test Bank"), eq("en"));
        verify(tenants).audit(eq(checker), eq(bankId), eq("COMPLAINT_RESOLVED"), eq(5L), any(), any(), contains("Approved request"), any(), any(), anyMap());
    }

    @Test void nonAdministratorsCannotApply() {
        var staff = new Actor("test-issuer", "teller", false, UUID.randomUUID());
        assertThatThrownBy(() -> service.apply(staff, bankId, resolution(4, 3, Outcome.UPHELD, Redress.APOLOGY, null, null), "maker", UUID.randomUUID()))
                .isInstanceOfSatisfying(TenantException.class, e -> assertThat(e.code()).isEqualTo("ACCESS_DENIED"));
        verifyNoInteractions(jdbc);
    }
}
