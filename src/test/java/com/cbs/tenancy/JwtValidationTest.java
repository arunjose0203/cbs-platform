package com.cbs.tenancy;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtValidationTest {
    @Test void acceptsCorrectlySignedAndScopedIdentity() {
        assertThat(TestIdentity.decoder().decode(TestIdentity.token("administrator", true)).getSubject()).isEqualTo("administrator");
    }
    @Test void rejectsOtherIssuer() {
        reject(TestIdentity.token(TestIdentity.KEY, "https://other.test", TestIdentity.AUDIENCE, "administrator", true, Instant.now().plusSeconds(300)));
    }
    @Test void rejectsOtherAudience() {
        reject(TestIdentity.token(TestIdentity.KEY, TestIdentity.ISSUER, "other-api", "administrator", true, Instant.now().plusSeconds(300)));
    }
    @Test void rejectsExpiredToken() {
        reject(TestIdentity.token(TestIdentity.KEY, TestIdentity.ISSUER, TestIdentity.AUDIENCE, "administrator", true, Instant.now().minusSeconds(300)));
    }
    @Test void rejectsMissingRequiredClaims() {
        reject(TestIdentity.token(TestIdentity.KEY, TestIdentity.ISSUER, TestIdentity.AUDIENCE, "administrator", true, null));
        reject(TestIdentity.token(TestIdentity.KEY, TestIdentity.ISSUER, TestIdentity.AUDIENCE, null, true, Instant.now().plusSeconds(300)));
        reject(TestIdentity.token(TestIdentity.KEY, TestIdentity.ISSUER, null, "administrator", true, Instant.now().plusSeconds(300)));
        reject(TestIdentity.token(TestIdentity.KEY, null, TestIdentity.AUDIENCE, "administrator", true, Instant.now().plusSeconds(300)));
    }
    @Test void rejectsUntrustedSignature() {
        reject(TestIdentity.token(TestIdentity.key(), TestIdentity.ISSUER, TestIdentity.AUDIENCE, "administrator", true, Instant.now().plusSeconds(300)));
    }
    private static void reject(String token) {
        assertThatThrownBy(() -> TestIdentity.decoder().decode(token)).isInstanceOf(JwtException.class);
    }
}
