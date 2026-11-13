package com.cbs.customers;

import com.cbs.access.AccessService;
import com.cbs.customers.CustomerModels.*;
import com.cbs.privacy.PersonalDataCipher;
import com.cbs.tenancy.*;
import com.cbs.tenancy.TenantModels.Actor;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Encrypted storage for KYC documents with retention controls (Round 12).
 *
 * <p>P-10: each bank's document key is derived from the configured platform field key
 * ({@link PersonalDataCipher#documentKey}), never from a value in the source code, and each ciphertext is bound to
 * its bank, customer and document as AES-GCM associated data, so rows cannot be swapped between customers. Rows
 * written before P-10 under the source-derived key ({@code master-v1}) are still readable; they are re-encrypted at
 * startup and on access, and the database trigger allows exactly that one change.
 */
@Component
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class CustomerDocumentService {
    static final String KEY_ID = "pd1-doc", LEGACY_KEY_ID = "master-v1";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_RETENTION_YEARS = 7;
    /** Scanned identity and evidence documents only; nothing a browser would execute or render as a page. */
    static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/tiff");
    /** Read-only: decrypts rows written before P-10. Never used to encrypt. */
    private static final byte[] LEGACY_SALT = "cbs-kyc-aes-gcm-master-salt-2026".getBytes(StandardCharsets.UTF_8);

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final PersonalDataCipher keys;
    private final SecureRandom random = new SecureRandom();

    public CustomerDocumentService(JdbcTemplate jdbc, TenantRepository tenants, PersonalDataCipher keys) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.keys = keys;
    }

    public DocumentSummary upload(Actor actor, UUID bankId, UUID customerId, DocumentUpload input) {
        AccessService.admin(actor);
        var bank = tenants.get(bankId, false);
        if (bank.status() != TenantModels.Status.ACTIVE) throw new TenantException(409, "CUSTOMER_BANK_NOT_ACTIVE", "Bank is not active");
        if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.customer_registry WHERE tenant_id=? AND id=?)", Boolean.class, bankId, customerId)) {
            throw new TenantException(404, "CUSTOMER_NOT_FOUND", "Customer not found");
        }
        String contentType = input.contentType().trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new TenantException(400, "DOCUMENT_TYPE_NOT_ALLOWED", "Upload a PDF, PNG, JPEG or TIFF document");
        }
        String fileName = fileName(input.fileName());

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

        UUID docId = UUID.randomUUID();
        String sha256 = sha256(plaintext);
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] ciphertext = encrypt(bankId, customerId, docId, iv, plaintext);

        Instant now = Instant.now();
        jdbc.update("INSERT INTO platform.customer_document(id,tenant_id,customer_id,document_type,file_name,content_type,file_size,sha256,ciphertext,iv,key_id,retention_until,uploaded_by,uploaded_at,archived) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false)",
                docId, bankId, customerId, input.documentType().name(), fileName, contentType, plaintext.length, sha256, ciphertext, iv, KEY_ID, retention, actor.subject(), Timestamp.from(now));

        tenants.audit(actor, bankId, "CUSTOMER_DOCUMENT_UPLOADED", bank.version(), bank.status(), bank.status(),
                "KYC document uploaded", null, null,
                Map.of("customerId", customerId, "documentId", docId, "documentType", input.documentType().name(), "sha256", sha256));

        return new DocumentSummary(docId, customerId, input.documentType().name(), fileName, contentType, plaintext.length, sha256, retention, now, actor.subject(), false);
    }

    public List<DocumentSummary> list(Actor actor, UUID bankId, UUID customerId) {
        AccessService.admin(actor);
        tenants.get(bankId, false);
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
        var rows = jdbc.query("SELECT id,file_name,content_type,sha256,ciphertext,iv,key_id FROM platform.customer_document WHERE tenant_id=? AND customer_id=? AND id=?",
                (r, n) -> new Object[]{
                        r.getObject("id", UUID.class),
                        r.getString("file_name"),
                        r.getString("content_type"),
                        r.getString("sha256"),
                        r.getBytes("ciphertext"),
                        r.getBytes("iv"),
                        r.getString("key_id")
                }, bankId, customerId, documentId);

        if (rows.isEmpty()) throw new TenantException(404, "DOCUMENT_NOT_FOUND", "Customer document not found");
        Object[] row = rows.getFirst();
        String fileName = (String) row[1];
        String contentType = (String) row[2];
        String sha256 = (String) row[3];
        byte[] plaintext = decrypt(bankId, customerId, documentId, (byte[]) row[5], (byte[]) row[4], (String) row[6]);
        if (!sha256(plaintext).equalsIgnoreCase(sha256)) {
            throw new TenantException(500, "DOCUMENT_INTEGRITY_COMPROMISED", "Document integrity check failed");
        }
        if (LEGACY_KEY_ID.equals(row[6])) reencrypt(bankId, customerId, documentId, plaintext);

        tenants.audit(actor, bankId, "CUSTOMER_DOCUMENT_DOWNLOADED", bank.version(), bank.status(), bank.status(),
                "KYC document accessed", null, null,
                Map.of("customerId", customerId, "documentId", documentId));

        return new DecryptedDocument(documentId, fileName, contentType, plaintext);
    }

    // ---- Archiving (P-10: maker-checker through ApprovalService action CUSTOMER_DOCUMENT_ARCHIVE) ----

    public void validateArchive(TenantModels.Tenant bank, DocumentArchive change) {
        if (bank.version() != change.expectedVersion()) throw new TenantException(409, "STALE_VERSION", "The bank changed; reload and try again");
        var rows = jdbc.query("SELECT retention_until,archived FROM platform.customer_document WHERE tenant_id=? AND customer_id=? AND id=?",
                (r, n) -> new Object[]{r.getDate("retention_until").toLocalDate(), r.getBoolean("archived")}, bank.id(), change.customerId(), change.documentId());
        if (rows.isEmpty()) throw new TenantException(404, "DOCUMENT_NOT_FOUND", "Customer document not found");
        if ((Boolean) rows.getFirst()[1]) throw new TenantException(409, "DOCUMENT_ALREADY_ARCHIVED", "Customer document is already archived");
        LocalDate retentionUntil = (LocalDate) rows.getFirst()[0];
        if (retentionUntil.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new TenantException(409, "DOCUMENT_RETENTION_ACTIVE", "Document retention active until " + retentionUntil + "; purge prohibited");
        }
    }

    public Map<String, Object> archiveBefore(UUID bankId, DocumentArchive change) {
        var rows = jdbc.queryForList("SELECT document_type,file_name,retention_until FROM platform.customer_document WHERE tenant_id=? AND id=?", bankId, change.documentId());
        if (rows.isEmpty()) return Map.of();
        var row = rows.getFirst();
        return Map.of("document", row.get("document_type") + " " + row.get("file_name"), "retentionUntil", row.get("retention_until").toString(), "archived", false);
    }

    public Map<String, Object> archiveValues(DocumentArchive change) {
        return Map.of("customerId", change.customerId(), "documentId", change.documentId(), "archived", true, "reason", change.reason());
    }

    public void applyArchive(Actor checker, UUID bankId, DocumentArchive change, UUID approvalId) {
        AccessService.admin(checker);
        var bank = tenants.get(bankId, true);
        validateArchive(bank, change);
        jdbc.update("UPDATE platform.customer_document SET archived=true WHERE tenant_id=? AND customer_id=? AND id=?", bankId, change.customerId(), change.documentId());
        tenants.change(bank, bank.status(), bank.displayName(), bank.defaultLanguage());
        tenants.audit(checker, bankId, "CUSTOMER_DOCUMENT_ARCHIVED", bank.version() + 1, bank.status(), bank.status(),
                "Approved request " + approvalId, null, null,
                Map.of("customerId", change.customerId(), "documentId", change.documentId(), "requestId", approvalId));
    }

    /** Re-encrypts every document still under the source-derived key; returns how many were migrated. */
    public int reencryptLegacy() {
        var legacy = jdbc.query("SELECT id,tenant_id,customer_id,sha256,ciphertext,iv FROM platform.customer_document WHERE key_id=? ORDER BY uploaded_at,id",
                (r, n) -> new Object[]{r.getObject("id", UUID.class), r.getObject("tenant_id", UUID.class), r.getObject("customer_id", UUID.class),
                        r.getString("sha256"), r.getBytes("ciphertext"), r.getBytes("iv")}, LEGACY_KEY_ID);
        int done = 0;
        for (var row : legacy) {
            UUID doc = (UUID) row[0], bank = (UUID) row[1], customer = (UUID) row[2];
            try {
                byte[] plaintext = decrypt(bank, customer, doc, (byte[]) row[5], (byte[]) row[4], LEGACY_KEY_ID);
                if (!sha256(plaintext).equalsIgnoreCase((String) row[3])) throw new IllegalStateException();
                done += reencrypt(bank, customer, doc, plaintext);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(CustomerDocumentService.class).error("KYC document {} could not be re-encrypted; it stays under the legacy key", doc);
            }
        }
        return done;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reencryptAtStartup() {
        try {
            int migrated = reencryptLegacy();
            if (migrated > 0) LoggerFactory.getLogger(CustomerDocumentService.class).info("Re-encrypted {} KYC documents under the field-key-derived key", migrated);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(CustomerDocumentService.class).warn("KYC document re-encryption deferred: store unavailable");
        }
    }

    private int reencrypt(UUID bankId, UUID customerId, UUID documentId, byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] ciphertext = encrypt(bankId, customerId, documentId, iv, plaintext);
        return jdbc.update("UPDATE platform.customer_document SET ciphertext=?,iv=?,key_id=? WHERE id=? AND tenant_id=? AND key_id=?",
                ciphertext, iv, KEY_ID, documentId, bankId, LEGACY_KEY_ID);
    }

    private byte[] encrypt(UUID bankId, UUID customerId, UUID documentId, byte[] iv, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.documentKey(bankId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(aad(bankId, customerId, documentId));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new TenantException(500, "DOCUMENT_ENCRYPTION_FAILED", "Document could not be stored");
        }
    }

    private byte[] decrypt(UUID bankId, UUID customerId, UUID documentId, byte[] iv, byte[] ciphertext, String keyId) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (KEY_ID.equals(keyId)) {
                cipher.init(Cipher.DECRYPT_MODE, keys.documentKey(bankId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                cipher.updateAAD(aad(bankId, customerId, documentId));
            } else if (LEGACY_KEY_ID.equals(keyId)) {
                cipher.init(Cipher.DECRYPT_MODE, legacyKey(bankId), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            } else {
                throw new TenantException(500, "DOCUMENT_DECRYPTION_FAILED", "Document could not be read");
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TenantException(500, "DOCUMENT_DECRYPTION_FAILED", "Document could not be read");
        }
    }

    static byte[] aad(UUID bankId, UUID customerId, UUID documentId) {
        return ("cbs-doc1:" + bankId + ":" + customerId + ":" + documentId).getBytes(StandardCharsets.UTF_8);
    }

    /** File name kept for display and download: no path, no control or quote characters, at most 255 characters. */
    static String fileName(String raw) {
        String name = raw.trim();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>:*?|;]", "_");
        if (name.isBlank() || name.chars().allMatch(c -> c == '.')) throw new TenantException(400, "DOCUMENT_NAME_INVALID", "Document file name is not valid");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static SecretKeySpec legacyKey(UUID bankId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(LEGACY_SALT, "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(bankId.toString().getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Legacy document key unavailable");
        }
    }
}
