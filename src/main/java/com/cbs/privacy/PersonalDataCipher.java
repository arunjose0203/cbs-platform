package com.cbs.privacy;

import com.cbs.tenancy.TenantException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Field-level protection for sensitive identifiers (Round 18): identity document numbers today.
 *
 * <p>{@link #seal} encrypts with AES-256-GCM under a key derived per bank from the platform field key.
 * The IV is an HMAC of the plaintext, so sealing is deterministic: the same value sealed twice gives
 * the same token. That keeps byte-identical payload checks (idempotent retries, bank receipts and
 * control/bank reconciliation) working; it reveals only whether two values are equal, which the
 * duplicate-document index already reveals. {@link #index} is a keyed blind index for uniqueness
 * and duplicate checks, so the registry never holds the number itself.
 *
 * <p>Values without the {@code pd1:} prefix are earlier plaintext records and are returned as they
 * are; they are not rewritten, because customer events are immutable evidence.
 */
@Component
public class PersonalDataCipher {
    static final String TOKEN = "pd1:", INDEX = "bi1:";
    /** Key used only when no key is configured outside production; ProductionGuard refuses it. */
    public static final String DEVELOPMENT_KEY = Base64.getEncoder().encodeToString(sha256("cbs-development-only-field-key"));
    private final byte[] master;
    private final boolean development;

    public PersonalDataCipher(@Value("${cbs.privacy.field-key:}") String key) {
        development = key == null || key.isBlank() || key.equals(DEVELOPMENT_KEY);
        if (development) LoggerFactory.getLogger(PersonalDataCipher.class).warn("Personal-data field key is the development key; never use it with real customer data");
        master = decode(development ? DEVELOPMENT_KEY : key);
    }

    /** Decoded key, or an exception naming the problem without echoing the value. */
    public static boolean developmentKey(String key) { return key != null && DEVELOPMENT_KEY.equals(key.strip()); }

    public static byte[] decode(String key) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(key.strip()); } catch (IllegalArgumentException e) { throw new IllegalStateException("cbs.privacy.field-key must be Base64"); }
        if (bytes.length < 32) throw new IllegalStateException("cbs.privacy.field-key must hold at least 32 random bytes");
        return bytes;
    }

    public boolean development() { return development; }

    public static boolean sealed(String value) { return value != null && value.startsWith(TOKEN); }

    public String seal(UUID bank, String plaintext) {
        if (plaintext == null || sealed(plaintext)) return plaintext;
        try {
            byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] iv = Arrays.copyOf(mac(derive(bank, "iv"), data), 12);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive(bank, "enc"), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(bank));
            byte[] sealed = cipher.doFinal(data);
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return TOKEN + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) { throw unavailable(); }
    }

    public String open(UUID bank, String value) {
        if (!sealed(value)) return value;
        try {
            byte[] in = Base64.getUrlDecoder().decode(value.substring(TOKEN.length()));
            if (in.length < 12 + 16) throw unavailable();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(bank, "enc"), "AES"), new GCMParameterSpec(128, in, 0, 12));
            cipher.updateAAD(aad(bank));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw unavailable(); }
    }

    /** Keyed blind index of an already normalized value, scoped to one bank. */
    public String index(UUID bank, String normalized) {
        return INDEX + HexFormat.of().formatHex(mac(derive(bank, "index"), normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] derive(UUID bank, String purpose) { return mac(master, ("cbs-pd1:" + purpose + ":" + bank).getBytes(StandardCharsets.UTF_8)); }
    private static byte[] aad(UUID bank) { return ("cbs-pd1:" + bank).getBytes(StandardCharsets.UTF_8); }
    private static byte[] mac(byte[] key, byte[] data) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC-SHA256 unavailable", e); }
    }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static TenantException unavailable() { return new TenantException(503, "PERSONAL_DATA_KEY_UNAVAILABLE", "Protected customer data could not be read"); }
}
