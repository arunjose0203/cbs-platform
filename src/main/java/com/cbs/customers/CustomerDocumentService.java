package com.cbs.customers;

import com.cbs.access.AccessService;
import com.cbs.customers.CustomerModels.*;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Encrypted object storage for KYC documents with retention controls.
 * Replaces reference-only KYC documents with AES-256-GCM encrypted payloads,
 * SHA-256 integrity verification, and tamper-resistant retention enforcement.
 */
@Component
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CustomerDocumentService {
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_RETENTION_YEARS = 7;
    private static final byte[] KEY_SALT = "cbs-kyc-aes-gcm-master-salt-2026".getBytes(StandardCharsets.UTF_8);

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final SecureRandom random = new SecureRandom();

    public CustomerDocumentService(JdbcTemplate jdbc, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public DocumentSummary upload(Actor actor, UUID bankId, UUID customerId, DocumentUpload input) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        if (bank.status() != TenantModels.Status.ACTIVE) throw new TenantException(409, "CUSTOMER_BANK_NOT_ACTIVE", "Bank is not active");
        if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=?)", Boolean.class, bankId, customerId)) {
            throw new TenantException(404, "CUSTOMER_NOT_FOUND", "Customer not found");
        }

        byte[] plaintext;
        try {
            plaintext = Base64.getDecoder().decode(input.base64Content());
        } catch (IllegalArgumentException e) {
            throw new TenantException(400, "DOCUMENT_CONTENT_INVALID", "Document content must be valid base64");
        }
        if (plaintext.length == 0 || plaintext.length > MAX_FILE_SIZE) {
            throw new TenantException(400, "DOCUMENT_SIZE_INVALID", "Document size must be between 1 byte and 10MB");
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate retention = input.retentionUntil() != null ? input.retentionUntil() : today.plusYears(DEFAULT_RETENTION_YEARS);
        if (retention.isBefore(today)) {
            throw new TenantException(400, "DOCUMENT_RETENTION_INVALID", "Retention date cannot be in the past");
        }

        String sha256;
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] ciphertext;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            sha256 = HexFormat.of().formatHex(digest.digest(plaintext));

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(bankId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            ciphertext = cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new TenantException(500, "DOCUMENT_ENCRYPTION_FAILED", "Encryption failure: " + e.getMessage());
        }

        UUID docId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO platform.customer_document(id,tenant_id,customer_id,document_type,file_name,content_type,file_size,sha256,ciphertext,iv,key_id,retention_until,uploaded_by,uploaded_at,archived) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false)",
                docId, bankId, customerId, input.documentType().name(), input.fileName().trim(), input.contentType().trim(), plaintext.length, sha256, ciphertext, iv, "master-v1", retention, actor.subject(), Timestamp.from(now));

        tenants.audit(actor, bankId, "CUSTOMER_DOCUMENT_UPLOADED", bank.version(), bank.status(), bank.status(),
                "KYC document uploaded: " + input.fileName(), null, null,
                Map.of("customerId", customerId, "documentId", docId, "documentType", input.documentType().name(), "sha256", sha256));

        return new DocumentSummary(docId, customerId, input.documentType().name(), input.fileName().trim(), input.contentType().trim(), plaintext.length, sha256, retention, now, actor.subject(), false);
    }

    public List<DocumentSummary> list(Actor actor, UUID bankId, UUID customerId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        return jdbc.query("SELECT id,customer_id,document_type,file_name,content_type,file_size,sha256,retention_until,uploaded_at,uploaded_by,archived FROM platform.customer_document WHERE tenant_id=? AND customer_id=? ORDER BY uploaded_at DESC",
                (r, n) -> new DocumentSummary(
                        r.getObject("id", UUID.class),
                        r.getObject("customer_id", UUID.class),
                        r.getString("document_type"),
                        r.getString("file_name"),
                        r.getString("content_type"),
                        r.getLong("file_size"),
                        r.getString("sha256"),
                        r.getDate("retention_until").toLocalDate(),
                        r.getTimestamp("uploaded_at").toInstant(),
                        r.getString("uploaded_by"),
                        r.getBoolean("archived")),
                bankId, customerId);
    }

    public DecryptedDocument download(Actor actor, UUID bankId, UUID customerId, UUID documentId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var rows = jdbc.query("SELECT id,file_name,content_type,sha256,ciphertext,iv FROM platform.customer_document WHERE tenant_id=? AND customer_id=? AND id=?",
                (r, n) -> new Object[]{
                        r.getObject("id", UUID.class),
                        r.getString("file_name"),
                        r.getString("content_type"),
                        r.getString("sha256"),
                        r.getBytes("ciphertext"),
                        r.getBytes("iv")
                }, bankId, customerId, documentId);

        if (rows.isEmpty()) throw new TenantException(404, "DOCUMENT_NOT_FOUND", "Customer document not found");
        Object[] row = rows.getFirst();
        String fileName = (String) row[1];
        String contentType = (String) row[2];
        String sha256 = (String) row[3];
        byte[] ciphertext = (byte[]) row[4];
        byte[] iv = (byte[]) row[5];

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(bankId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            plaintext = cipher.doFinal(ciphertext);

            String verifiedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
            if (!verifiedHash.equalsIgnoreCase(sha256)) {
                throw new TenantException(500, "DOCUMENT_INTEGRITY_COMPROMISED", "Document hash does not match stored SHA-256");
            }
        } catch (TenantException e) {
            throw e;
        } catch (Exception e) {
            throw new TenantException(500, "DOCUMENT_DECRYPTION_FAILED", "Decryption failure: " + e.getMessage());
        }

        tenants.audit(actor, bankId, "CUSTOMER_DOCUMENT_DOWNLOADED", bank.version(), bank.status(), bank.status(),
                "KYC document accessed: " + fileName, null, null,
                Map.of("customerId", customerId, "documentId", documentId));

        return new DecryptedDocument(documentId, fileName, contentType, plaintext);
    }

    public void archive(Actor actor, UUID bankId, UUID customerId, UUID documentId) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        var rows = jdbc.query("SELECT retention_until,archived FROM platform.customer_document WHERE tenant_id=? AND customer_id=? AND id=?",
                (r, n) -> new Object[]{r.getDate("retention_until").toLocalDate(), r.getBoolean("archived")}, bankId, customerId, documentId);
        if (rows.isEmpty()) throw new TenantException(404, "DOCUMENT_NOT_FOUND", "Customer document not found");

        LocalDate retentionUntil = (LocalDate) rows.getFirst()[0];
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (retentionUntil.isAfter(today)) {
            throw new TenantException(409, "DOCUMENT_RETENTION_ACTIVE", "Document retention active until " + retentionUntil + "; purge prohibited");
        }

        jdbc.update("UPDATE platform.customer_document SET archived=true WHERE tenant_id=? AND customer_id=? AND id=?", bankId, customerId, documentId);
        tenants.audit(actor, bankId, "CUSTOMER_DOCUMENT_ARCHIVED", bank.version(), bank.status(), bank.status(),
                "KYC document archived after retention expiration", null, null,
                Map.of("customerId", customerId, "documentId", documentId));
    }

    private static SecretKeySpec deriveKey(UUID bankId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY_SALT, "HmacSHA256"));
            byte[] keyBytes = mac.doFinal(bankId.toString().getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Document storage key derivation failed", e);
        }
    }
}
