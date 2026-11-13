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
import com.cbs.tenancy.TenantModels.LegalEntity;
import com.cbs.tenancy.TenantModels.Tenant;
import com.cbs.tenancy.TenantModels.Status;
import com.cbs.tenancy.TenantRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class StrDraftServiceTest {

    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private PlatformTransactionManager tm;
    private StrDraftService service;

    private final UUID bankId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final Actor actor = new Actor("http://test-idp", "compliance-lead", true, UUID.randomUUID());

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tenants = mock(TenantRepository.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new StrDraftService(jdbc, tenants, tm);
    }

    private Tenant tenant(long version) {
        var entity = new LegalEntity(UUID.randomUUID(), "Bank Ltd", "KE", "TAX123", "REG123");
        return new Tenant(bankId, "BANK", "Bank Ltd", "en", Status.ACTIVE, version, Instant.now(), Instant.now(), List.of(entity), null);
    }

    @Test
    void rejectsStaleBankVersion() {
        when(tenants.get(bankId, true)).thenReturn(tenant(5L));

        var request = new StrDraftService.Request(4L, 1L, "Unusual rapid fund movements", "Routine filing preparation");

        assertThatThrownBy(() -> service.draft(actor, bankId, caseId, request))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "STALE_VERSION");
    }

    @Test
    void rejectsNonExistentCase() {
        when(tenants.get(bankId, true)).thenReturn(tenant(5L));
        when(jdbc.queryForList(anyString(), eq(bankId), eq(caseId), eq(actor.issuer()))).thenReturn(List.of());

        var request = new StrDraftService.Request(5L, 1L, "Unusual rapid fund movements", "Routine filing preparation");

        assertThatThrownBy(() -> service.draft(actor, bankId, caseId, request))
                .isInstanceOf(TenantException.class)
                .hasFieldOrPropertyWithValue("code", "COMPLIANCE_CASE_NOT_FOUND");
    }

    @Test
    void generatesStrDraftAndRecordsEvent() throws Exception {
        when(tenants.get(bankId, true)).thenReturn(tenant(5L));

        var caseRow = new LinkedHashMap<String, Object>();
        caseRow.put("id", caseId);
        caseRow.put("customer_id", customerId);
        caseRow.put("version", 1L);
        caseRow.put("category", "TRANSACTION_REVIEW");
        caseRow.put("status", "OPEN");
        caseRow.put("risk", "HIGH");
        caseRow.put("assignee", "investigator-1");
        caseRow.put("due_date", "2026-11-20");
        caseRow.put("opened_by", "compliance-lead");
        caseRow.put("created_at", "2026-11-13T10:00:00Z");
        caseRow.put("customer_number", "CUST-001");
        caseRow.put("customer_type", "INDIVIDUAL");
        caseRow.put("name_key", "john doe");
        caseRow.put("birth_date", "1985-04-12");

        when(jdbc.queryForList(anyString(), eq(bankId), eq(caseId), eq(actor.issuer()))).thenReturn(List.of(caseRow));

        when(jdbc.queryForMap(anyString(), eq(caseId))).thenReturn(Map.of(
                "version", 2L,
                "status", "OPEN",
                "risk", "HIGH",
                "reviewed_customer_version", 1L,
                "review_due", "2026-11-30",
                "assignee", "investigator-1",
                "due_date", "2026-11-20"
        ));

        var request = new StrDraftService.Request(5L, 1L, "Large velocity of deposits followed by immediate cash withdrawals", "Suspicion of structuring");
        Map<String, Object> result = service.draft(actor, bankId, caseId, request);

        assertThat(result).containsKey("sha256");
        assertThat(result.get("fileName").toString()).startsWith("str-draft-CUST-001-");

        String docJson = (String) result.get("document");
        var doc = JsonMapper.builder().build().readValue(docJson, Map.class);
        assertThat(doc.get("format")).isEqualTo("cbs-str-draft/1");
        assertThat(doc.get("status")).isEqualTo("DRAFT_NOT_FILED");
        assertThat(doc.get("grounds")).isEqualTo("Large velocity of deposits followed by immediate cash withdrawals");

        verify(jdbc).update(eq("UPDATE platform.compliance_case SET version=version+1,updated_at=clock_timestamp() WHERE id=?"), eq(caseId));
        verify(tenants).audit(eq(actor), eq(bankId), eq("STR_DRAFT_EXPORTED"), eq(5L), eq(Status.ACTIVE), eq(Status.ACTIVE),
                eq("Suspicion of structuring"), eq(null), eq(null), any());
    }
}
