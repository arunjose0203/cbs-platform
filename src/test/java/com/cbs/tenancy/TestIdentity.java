package com.cbs.tenancy;

import com.cbs.foundation.security.IdentityConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class TestIdentity {
    public static final String ISSUER = "https://identity.cbs.test";
    public static final String AUDIENCE = "cbs-api";
    static final RSAKey KEY = key();
    private TestIdentity() { }

    public static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("test-key").generate(); }
        catch (JOSEException exception) { throw new IllegalStateException(exception); }
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(IdentityConfiguration.validators(ISSUER, AUDIENCE));
            return decoder;
        } catch (JOSEException exception) { throw new IllegalStateException(exception); }
    }

    public static String token(String subject, boolean admin) {
        return token(KEY, ISSUER, AUDIENCE, subject, admin, Instant.now().plusSeconds(300));
    }

    public static String token(RSAKey key, String issuer, String audience, String subject, boolean admin, Instant expires) {
        return token(key,issuer,audience,subject,admin,expires,Instant.now(),Instant.now(),java.util.UUID.randomUUID().toString(),true);
    }

    public static String token(RSAKey key,String issuer,String audience,String subject,boolean admin,Instant expires,
            Instant issued,Instant authTime,String sid,boolean mfa) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                    .issueTime(issued==null?null:Date.from(issued)).claim("roles", admin ? List.of("PLATFORM_ADMIN") : List.of())
                    .claim("auth_time",authTime==null?null:authTime.getEpochSecond()).claim("sid",sid)
                    .claim("amr",mfa?List.of("pwd","mfa"):List.of("pwd"));
            if (expires != null) { claims.expirationTime(Date.from(expires)); }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) { throw new IllegalStateException(exception); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean @Primary
        JwtDecoder testJwtDecoder() { return decoder(); }
    }
}
