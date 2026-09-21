package com.cbs.foundation.security;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityConfiguration.Settings.class)
public class IdentityConfiguration {
    @ConfigurationProperties("cbs.security")
    public record Settings(boolean enabled, String issuer, String jwkSetUri, String audience,
            boolean allowLocalHttp) {
        public Settings {
            if (enabled) {
                requireIdentityUrl(issuer, allowLocalHttp);
                requireIdentityUrl(jwkSetUri, allowLocalHttp);
            }
        }
        public String localConnectOrigin() {
            if (!enabled || !allowLocalHttp || !issuer.startsWith("http://")) { return ""; }
            URI uri = URI.create(issuer);
            return " http://" + uri.getRawAuthority();
        }
    }

    @Bean
    @ConditionalOnProperty(name = "cbs.security.enabled", havingValue = "true")
    JwtDecoder configuredJwtDecoder(Settings settings) {
        if (settings.audience() == null || settings.audience().isBlank()) {
            throw new IllegalStateException("CBS security audience must be configured");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).build();
        decoder.setJwtValidator(validators(settings.issuer(), settings.audience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            String subject = jwt.getSubject();
            boolean valid = jwt.getExpiresAt() != null && subject != null && !subject.isBlank()
                    && subject.length() <= 200 && subject.chars().noneMatch(Character::isISOControl)
                    && issuer.length() <= 512 && jwt.getAudience() != null && jwt.getAudience().contains(audience);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required token claims are invalid", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), requiredClaims);
    }

    private static void requireIdentityUrl(String value, boolean allowLocalHttp) {
        try {
            URI uri = URI.create(value);
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            boolean development = allowLocalHttp && loopback && "http".equals(uri.getScheme());
            if ((!"https".equals(uri.getScheme()) && !development) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("CBS identity URLs require HTTPS; local HTTP requires explicit development configuration and a loopback host");
        }
    }
}
