package com.cbs.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cbs.access.AccessService;
import com.cbs.access.BankDuties;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantModels.LegalEntity;
import com.cbs.tenancy.TenantModels.Status;
import com.cbs.tenancy.TenantModels.Tenant;
import com.cbs.tenancy.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class ComplianceAssignmentTest {

    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private PlatformTransactionManager tm;
    private AccessService access;
    private BankDuties duties;
    private ComplianceService service;

    private final UUID bankId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final Actor actor = new Actor("http://test-idp", "compliance-admin", true, UUID.randomUUID());

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tenants = mock(TenantRepository.class);
        tm = mock(PlatformTransactionManager.class);
        access = mock(AccessService.class);
        duties = mock(BankDuties.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new ComplianceService(jdbc, tenants, tm);
        service.access(access, duties);
    }

    private Tenant tenant(long version) {
        var entity = new LegalEntity(UUID.randomUUID(), "Bank Ltd", "KE", "TAX123", "REG123");
        return new Tenant(bankId, "BANK", "Bank Ltd", "en", Status.ACTIVE, version, Instant.now(), Instant.now(), List.of(entity), null);
    }

    private Map<String, Object> caseRow(long version, String status, String assignee, LocalDate dueDate) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", caseId);
        row.put("tenant_id", bankId);
        row.put("issuer", actor.issuer());
        row.put("version", version);
        row.put("status", status);
        row.put("assignee", assignee);
        row.put("due_date", dueDate);
        row.put("customer_number", "CUST-001");
        row.put("customer_version", 1L);
        row.put("customer_delivery", "APPLIED");
        row.put("restricted", true);
        return row;
    }

    private void mockCase(long version, String status, String assignee, LocalDate dueDate) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("compliance_command")) {
                return List.of();
            }
            if (sql.contains("compliance_case")) {
                return List.of(caseRow(version, status, assignee, dueDate));
            }
            return List.of();
        });
    }

    @Test
    void rejectsDueDateInThePast() {
        when(tenants.get(bankId, true)).thenReturn(tenant(1L));
        mockCase(1L, "OPEN", null, null);

        var input = new ComplianceModels.Assign(1L, 1L, "investigator-1", LocalDate.now().minusDays(1), "Assignment SLA");

        assertThatThrownBy(() -> service.assign(actor, bankId, caseId, UUID.randomUUID(), input))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "COMPLIANCE_DUE_DATE_INVALID");
    }

    @Test
    void rejectsDueDateBeyondOneYear() {
        when(tenants.get(bankId, true)).thenReturn(tenant(1L));
        mockCase(1L, "OPEN", null, null);

        var input = new ComplianceModels.Assign(1L, 1L, "investigator-1", LocalDate.now().plusDays(400), "Assignment SLA");

        assertThatThrownBy(() -> service.assign(actor, bankId, caseId, UUID.randomUUID(), input))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "COMPLIANCE_DUE_DATE_INVALID");
    }

    @Test
    void rejectsWhenCaseIsNotOpen() {
        when(tenants.get(bankId, true)).thenReturn(tenant(1L));
        mockCase(1L, "CLEARED", null, null);

        var input = new ComplianceModels.Assign(1L, 1L, "investigator-1", LocalDate.now().plusDays(7), "Assignment SLA");

        assertThatThrownBy(() -> service.assign(actor, bankId, caseId, UUID.randomUUID(), input))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "COMPLIANCE_CASE_NOT_OPEN");
    }

    @Test
    void rejectsAssigneeWithoutComplianceDuty() {
        when(tenants.get(bankId, true)).thenReturn(tenant(1L));
        mockCase(1L, "OPEN", null, null);
        when(duties.holds(bankId, actor.issuer(), "unauthorized-staff", BankDuties.Duty.COMPLIANCE)).thenReturn(false);

        var input = new ComplianceModels.Assign(1L, 1L, "unauthorized-staff", LocalDate.now().plusDays(7), "Assignment SLA");

        assertThatThrownBy(() -> service.assign(actor, bankId, caseId, UUID.randomUUID(), input))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "COMPLIANCE_ASSIGNEE_NOT_AUTHORIZED");
    }

    @Test
    void assignsSuccessfullyWhenAuthorized() {
        when(tenants.get(bankId, true)).thenReturn(tenant(1L));
        when(tenants.get(bankId, false)).thenReturn(tenant(1L));
        mockCase(1L, "OPEN", null, null);
        when(duties.holds(bankId, actor.issuer(), "investigator-1", BankDuties.Duty.COMPLIANCE)).thenReturn(true);

        LocalDate dueDate = LocalDate.now().plusDays(7);
        var input = new ComplianceModels.Assign(1L, 1L, "investigator-1", dueDate, "Assigned for source of wealth review");
        UUID key = UUID.randomUUID();

        Map<String, Object> result = service.assign(actor, bankId, caseId, key, input);
        assertThat(result).containsKey("case");

        verify(access).requireAssignable(actor.issuer(), "investigator-1");
        verify(jdbc).update(eq("UPDATE platform.compliance_case SET assignee=?,due_date=?,version=version+1,updated_at=clock_timestamp() WHERE id=?"),
                eq("investigator-1"), eq(dueDate), eq(caseId));
    }
}
