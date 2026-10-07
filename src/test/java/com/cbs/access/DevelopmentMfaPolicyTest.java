package com.cbs.access;

import static org.assertj.core.api.Assertions.*;
import com.cbs.foundation.security.IdentityConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class DevelopmentMfaPolicyTest {
    private final String issuer = "http://127.0.0.1:8180/realms/cbs-dev";
    private final AccessConfiguration.Settings access = new AccessConfiguration.Settings(null,null,null,null,"admin-one");
    private final IdentityConfiguration.Settings local = new IdentityConfiguration.Settings(true,issuer,issuer+"/certs","cbs-api",true);

    private Jwt token(String tokenIssuer, String subject) {
        return Jwt.withTokenValue("test-only").header("alg","RS256")
                .issuer(tokenIssuer).subject(subject).build();
    }

    @Test void disabledByDefault() {
        assertThat(new DevelopmentMfaPolicy(local,access,"","127.0.0.1")
                .allows(token(issuer,"admin-one"),Instant.now())).isFalse();
    }

    @Test void deadlineIsCheckedOnEveryRequestAndOnlyForTheNamedIdentity() {
        Instant deadline = Instant.now().plusSeconds(60);
        var policy = new DevelopmentMfaPolicy(local,access,deadline.toString(),"127.0.0.1");
        assertThat(policy.allows(token(issuer,"admin-one"),deadline.minusSeconds(1))).isTrue();
        assertThat(policy.allows(token(issuer,"admin-one"),deadline)).isFalse();
        assertThat(policy.expiredFor(token(issuer,"admin-one"),deadline)).isTrue();
        assertThat(policy.expiredFor(token(issuer,"admin-one"),deadline.minusSeconds(1))).isFalse();
        assertThat(policy.expiredFor(token(issuer,"other-admin"),deadline)).isFalse();
        assertThat(policy.expiredFor(token("http://localhost:8180/other","admin-one"),deadline)).isFalse();
        assertThat(policy.allows(token(issuer,"other-admin"),deadline.minusSeconds(1))).isFalse();
        assertThat(policy.allows(token("http://localhost:8180/other","admin-one"),deadline.minusSeconds(1))).isFalse();
    }

    @Test void refusesRemoteIdentityExternalBindAndUnboundedDeadline() {
        String deadline = Instant.now().plusSeconds(60).toString();
        var remote = new IdentityConfiguration.Settings(true,"https://id.example/realm","https://id.example/keys","cbs-api",true);
        assertThatThrownBy(() -> new DevelopmentMfaPolicy(remote,access,deadline,"127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DevelopmentMfaPolicy(local,access,deadline,"0.0.0.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DevelopmentMfaPolicy(local,access,Instant.now().plusSeconds(90000).toString(),"127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void expiredExceptionRejectsPasswordOnlyButStillAcceptsRealMfaEvidence() {
        Instant now=Instant.now();
        var policy=new DevelopmentMfaPolicy(local,access,now.minusSeconds(1).toString(),"127.0.0.1");
        var service=new AccessService(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),access,policy);
        var builder=Jwt.withTokenValue("test-only").header("alg","RS256").issuer(issuer).subject("admin-one")
                .issuedAt(now.minusSeconds(10)).expiresAt(now.plusSeconds(300)).claim("sid","test-session")
                .claim("auth_time",now.minusSeconds(10).getEpochSecond());
        assertThatThrownBy(()->service.authenticate(builder.claim("amr",java.util.List.of("pwd")).build(),true))
                .isInstanceOfSatisfying(com.cbs.tenancy.TenantException.class,error->assertThat(error.code()).isEqualTo("LOCAL_MFA_EXCEPTION_EXPIRED"));
        // Real MFA passes the factor check and reaches the (empty mocked) staff directory.
        assertThatThrownBy(()->service.authenticate(builder.claim("amr",java.util.List.of("pwd","mfa")).build(),true))
                .isInstanceOfSatisfying(com.cbs.tenancy.TenantException.class,error->assertThat(error.code()).isEqualTo("STAFF_NOT_REGISTERED"));
    }
}
