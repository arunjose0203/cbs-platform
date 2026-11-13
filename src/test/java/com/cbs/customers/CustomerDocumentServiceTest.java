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

    static final String FIELD_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
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
        service = new CustomerDocumentService(jdbc, tenants, new com.cbs.privacy.PersonalDataCipher(FIELD_KEY));
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
                .thenReturn(Collections.singletonList(new Object[]{docId, "id-card.pdf", "application/pdf", sha256, ciphertext, iv, "pd1-doc"}));

        DecryptedDocument decrypted = service.download(adminActor, bankId, customerId, docId);
        assertThat(decrypted.id()).isEqualTo(docId);
        assertThat(decrypted.fileName()).isEqualTo("id-card.pdf");
        assertThat(decrypted.contentType()).isEqualTo("application/pdf");
        assertThat(new String(decrypted.data(), StandardCharsets.UTF_8)).isEqualTo("Confidential National ID Data 2026");
    }

    /** Uploads one document and returns {docId, sha256, ciphertext, iv} as written to the database. */
    private Object[] stored(CustomerDocumentService target, String text) {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        AtomicReference<Object[]> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(new Object[]{inv.getArgument(1), inv.getArgument(8), inv.getArgument(9), inv.getArgument(10), inv.getArgument(11)}); return 1; })
                .when(jdbc).update(startsWith("INSERT INTO platform.customer_document"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        target.upload(adminActor, bankId, customerId, new DocumentUpload(DocumentType.NATIONAL_ID, "id.pdf", "application/pdf", null,
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
        return captured.get();
    }

    private void row(UUID customer, UUID docId, String sha, byte[] ciphertext, byte[] iv, String keyId) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customer), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{docId, "id.pdf", "application/pdf", sha, ciphertext, iv, keyId}));
    }

    @Test
    void theKeyComesFromTheFieldKeyNotTheSourceCode() {
        Object[] saved = stored(service, "Confidential");
        assertThat(saved[4]).isEqualTo("pd1-doc");
        UUID docId = (UUID) saved[0];
        // The same database row cannot be read with another field key (e.g. someone holding only a backup and the source).
        var other = new CustomerDocumentService(jdbc, tenants, new com.cbs.privacy.PersonalDataCipher(
                Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII))));
        row(customerId, docId, (String) saved[1], (byte[]) saved[2], (byte[]) saved[3], "pd1-doc");
        assertThatThrownBy(() -> other.download(adminActor, bankId, customerId, docId))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_DECRYPTION_FAILED"))
                .hasMessageNotContaining("Tag").hasMessageNotContaining("mismatch");
        assertThat(service.download(adminActor, bankId, customerId, docId).data()).asString(StandardCharsets.UTF_8).isEqualTo("Confidential");
    }

    @Test
    void aCiphertextMovedToAnotherCustomerDoesNotDecrypt() {
        Object[] saved = stored(service, "Belongs to one customer");
        UUID docId = (UUID) saved[0], otherCustomer = UUID.randomUUID();
        row(otherCustomer, docId, (String) saved[1], (byte[]) saved[2], (byte[]) saved[3], "pd1-doc");
        assertThatThrownBy(() -> service.download(adminActor, bankId, otherCustomer, docId))
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_DECRYPTION_FAILED"));
    }

    @Test
    void legacyDocumentsStayReadableAndAreReencrypted() throws Exception {
        byte[] plaintext = "Written before P-10".getBytes(StandardCharsets.UTF_8);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("cbs-kyc-aes-gcm-master-salt-2026".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var legacy = new javax.crypto.spec.SecretKeySpec(mac.doFinal(bankId.toString().getBytes(StandardCharsets.UTF_8)), "AES");
        byte[] iv = new byte[12];
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, legacy, new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(plaintext));
        UUID docId = UUID.randomUUID();
        row(customerId, docId, sha, ciphertext, iv, "master-v1");
        when(jdbc.update(startsWith("UPDATE platform.customer_document SET ciphertext"), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(service.download(adminActor, bankId, customerId, docId).data()).isEqualTo(plaintext);
        verify(jdbc).update(startsWith("UPDATE platform.customer_document SET ciphertext"), any(byte[].class), any(byte[].class),
                eq("pd1-doc"), eq(docId), eq(bankId), eq("master-v1"));
    }

    @Test
    void onlyDocumentTypesAndSafeFileNamesAreStored() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(bankId), eq(customerId))).thenReturn(true);
        String data = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String type : List.of("text/html", "image/svg+xml", "application/javascript", "application/octet-stream"))
            assertThatThrownBy(() -> service.upload(adminActor, bankId, customerId, new DocumentUpload(DocumentType.PASSPORT, "a.pdf", type, null, data)))
                    .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_TYPE_NOT_ALLOWED"));
        assertThat(CustomerDocumentService.fileName("..\\..\\C:/evil\"name\r\n.pdf")).isEqualTo("evil_name__.pdf");
        assertThat(CustomerDocumentService.fileName("x".repeat(300) + ".pdf")).hasSize(255);
        assertThatThrownBy(() -> CustomerDocumentService.fileName("/..")).isInstanceOf(TenantException.class);
    }

    @Test
    void tamperedDocumentThrowsIntegrityCompromised() {
        UUID docId = UUID.randomUUID();
        byte[] fakeCiphertext = new byte[64];
        byte[] iv = new byte[12];
        String originalHash = "0000000000000000000000000000000000000000000000000000000000000000";

        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{docId, "passport.pdf", "application/pdf", originalHash, fakeCiphertext, iv, "pd1-doc"}));

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

        assertThatThrownBy(() -> service.validateArchive(activeBank, new DocumentArchive(1L, customerId, docId, "Retention ended")))
                .isInstanceOf(TenantException.class)
                .satisfies(e -> assertThat(((TenantException) e).code()).isEqualTo("DOCUMENT_RETENTION_ACTIVE"));
    }

    @Test
    void expiredRetentionAllowsArchiving() {
        UUID docId = UUID.randomUUID();
        LocalDate pastRetention = LocalDate.now(ZoneOffset.UTC).minusDays(1);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(bankId), eq(customerId), eq(docId)))
                .thenReturn(Collections.singletonList(new Object[]{pastRetention, false}));

        service.applyArchive(adminActor, bankId, new DocumentArchive(1L, customerId, docId, "Retention ended"), UUID.randomUUID());
        verify(jdbc).update(contains("archived=true"), eq(bankId), eq(customerId), eq(docId));
        verify(tenants).change(eq(activeBank), any(), any(), any());
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
