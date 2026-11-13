package com.cbs.privacy;

import static org.assertj.core.api.Assertions.*;
import com.cbs.tenancy.TenantException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalDataCipherTest {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private final PersonalDataCipher cipher = new PersonalDataCipher(KEY);
    private final UUID bank = UUID.fromString("00000000-0000-0000-0000-00000000000a"), other = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test void sealsDeterministicallyPerBankAndOpensAgain() {
        String token = cipher.seal(bank, "A1234567");
        assertThat(token).startsWith("pd1:").doesNotContain("A1234567");
        assertThat(cipher.seal(bank, "A1234567")).isEqualTo(token);
        assertThat(cipher.seal(other, "A1234567")).isNotEqualTo(token);
        assertThat(cipher.seal(bank, "A1234568")).isNotEqualTo(token);
        assertThat(cipher.open(bank, token)).isEqualTo("A1234567");
        assertThat(cipher.seal(bank, token)).as("sealing twice is harmless").isEqualTo(token);
    }

    @Test void earlierPlaintextPassesThroughAndNullStaysNull() {
        assertThat(cipher.open(bank, "A1234567")).isEqualTo("A1234567");
        assertThat(cipher.seal(bank, null)).isNull();
    }

    @Test void refusesAnotherBanksKeyAnotherMasterKeyAndTampering() {
        String token = cipher.seal(bank, "A1234567");
        assertThatThrownBy(() -> cipher.open(other, token)).isInstanceOfSatisfying(TenantException.class, e -> assertThat(e.code()).isEqualTo("PERSONAL_DATA_KEY_UNAVAILABLE"));
        assertThatThrownBy(() -> new PersonalDataCipher("").open(bank, token)).isInstanceOf(TenantException.class);
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> cipher.open(bank, tampered)).isInstanceOf(TenantException.class);
        assertThatThrownBy(() -> cipher.open(bank, "pd1:!!")).isInstanceOf(TenantException.class);
    }

    @Test void blindIndexIsKeyedAndScopedToTheBank() {
        String index = cipher.index(bank, "KE:NATIONAL_ID:A1234567");
        assertThat(index).startsWith("bi1:").hasSize(68).doesNotContain("A1234567");
        assertThat(cipher.index(bank, "KE:NATIONAL_ID:A1234567")).isEqualTo(index);
        assertThat(cipher.index(other, "KE:NATIONAL_ID:A1234567")).isNotEqualTo(index);
        assertThat(new PersonalDataCipher("").index(bank, "KE:NATIONAL_ID:A1234567")).isNotEqualTo(index);
    }

    @Test void blankKeyIsTheDevelopmentKeyAndShortKeysAreRefused() {
        assertThat(new PersonalDataCipher("").development()).isTrue();
        assertThat(new PersonalDataCipher(PersonalDataCipher.DEVELOPMENT_KEY).development()).isTrue();
        assertThat(cipher.development()).isFalse();
        assertThatThrownBy(() -> new PersonalDataCipher("c2hvcnQ=")).hasMessageContaining("32 random bytes").hasMessageNotContaining("c2hvcnQ=");
        assertThatThrownBy(() -> new PersonalDataCipher("not base64!")).hasMessageContaining("Base64");
    }
}
