package com.cbs.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cbs.customers.CustomerModels.*;
import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels;
import com.cbs.tenancy.TenantModels.Actor;
import com.cbs.tenancy.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CustomerDocumentServiceTest {
    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private CustomerDocumentService service;

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
        service = new CustomerDocumentService(jdbc, tenants);
        when(tenants.get(eq(bankId), anyBoolean())).thenReturn(activeBank);
    }

    @Test
    void uploadAndDownloadRoundTripSucceeds() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);

        byte[] payload = "Confidential National ID Data 2026".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(payload);
        LocalDate retention = LocalDate.now(ZoneOffset.UTC).plusYears(5);

        DocumentUpload upload = new DocumentUpload(
                DocumentType.NATIONAL_ID,
                "id-card.pdf",
                "application/pdf",
                retention,
                base64
        );

        AtomicReference<Object[]> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(new Object[]{
                    inv.getArgument(1),  // docId
                    inv.getArgument(8),  // sha256
                    inv.getArgument(9),  // ciphertext
                    inv.getArgument(10)  // iv
            });
            return 1;
        }).when(jdbc).update(anyString(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        DocumentSummary summary = service.upload(adminActor, bankId, customerId, upload);

        assertThat(summary.fileName()).isEqualTo("id-card.pdf");
        assertThat(summary.fileSize()).isEqualTo(payload.length);
        assertThat(summary.retentionUntil()).isEqualTo(retention);
        assertThat(summary.archived()).isFalse();

        Object[] saved = captured.get();
        assertThat(saved).isNotNull();
        UUID docId = (UUID) saved[0];
        String sha256 = (String) saved[1];
        byte[] ciphertext = (byte[]) saved[2];
        byte[] iv = (byte[]) saved[3];

        // Mock download query using Collections.singletonList to avoid varargs flattening
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{docId, "id-card.pdf", "application/pdf", sha256, ciphertext, iv}));

        DecryptedDocument decrypted = service.download(adminActor, bankId, customerId, docId);
        assertThat(decrypted.id()).isEqualTo(docId);
        assertThat(decrypted.fileName()).isEqualTo("id-card.pdf");
        assertThat(decrypted.contentType()).isEqualTo("application/pdf");
        assertThat(new String(decrypted.data(), StandardCharsets.UTF_8)).isEqualTo("Confidential National ID Data 2026");
    }

    @Test
    void tamperedDocumentThrowsIntegrityCompromised() {
        UUID docId = UUID.randomUUID();
        byte[] fakeCiphertext = new byte[64];
        byte[] iv = new byte[12];
        String originalHash = "0000000000000000000000000000000000000000000000000000000000000000";

        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{docId, "passport.pdf", "application/pdf", originalHash, fakeCiphertext, iv}));

        assertThatThrownBy(() -> service.download(adminActor, bankId, customerId, docId))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> {
                    TenantException te = (TenantException) e;
                    assertThat(te.code()).isIn("DOCUMENT_INTEGRITY_COMPROMISED", "DOCUMENT_DECRYPTION_FAILED");
                });
    }

    @Test
    void activeRetentionPreventsPrematureArchiving() {
        UUID docId = UUID.randomUUID();
        LocalDate futureRetention = LocalDate.now(ZoneOffset.UTC).plusYears(3);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{futureRetention, false}));

        assertThatThrownBy(() -> service.archive(adminActor, bankId, customerId, docId))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_RETENTION_ACTIVE"));
    }

    @Test
    void expiredRetentionAllowsArchiving() {
        UUID docId = UUID.randomUUID();
        LocalDate pastRetention = LocalDate.now(ZoneOffset.UTC).minusDays(1);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{pastRetention, false}));

        service.archive(adminActor, bankId, customerId, docId);
        verify(jdbc).update(contains("archived=true"), eq(bankId), eq(customerId), eq(docId));
    }

    @Test
    void pastRetentionOnUploadIsRejected() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        DocumentUpload upload = new DocumentUpload(
                DocumentType.PASSPORT,
                "passport.pdf",
                "application/pdf",
                yesterday,
                Base64.getEncoder().encodeToString("data".getBytes(StandardCharsets.UTF_8))
        );

        assertThatThrownBy(() -> service.upload(adminActor, bankId, customerId, upload))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_RETENTION_INVALID"));
    }

    @Test
    void invalidBase64OrEmptyContentRejected() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);

        DocumentUpload invalidBase64 = new DocumentUpload(
                DocumentType.PASSPORT,
                "file.pdf",
                "application/pdf",
                null,
                "not-valid-base64!!!"
        );
        assertThatThrownBy(() -> service.upload(adminActor, bankId, customerId, invalidBase64))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_CONTENT_INVALID"));

        DocumentUpload emptyContent = new DocumentUpload(
                DocumentType.PASSPORT,
                "file.pdf",
                "application/pdf",
                null,
                ""
        );
        assertThatThrownBy(() -> service.upload(adminActor, bankId, customerId, emptyContent))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_SIZE_INVALID"));
    }
}
