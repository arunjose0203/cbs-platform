package com.cbs.access;

import com.cbs.foundation.security.IdentityConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Explicit, expiring exception for one administrator of a loopback development instance. */
@Component
public class DevelopmentMfaPolicy {
    private final Instant until;
    private final String issuer;
    private final String subject;

    public DevelopmentMfaPolicy(IdentityConfiguration.Settings identity, AccessConfiguration.Settings access,
            @Value("${cbs.access.local-mfa-bypass-until:}") String deadline,
            @Value("${server.address:127.0.0.1}") String bindAddress) {
        until = deadline == null || deadline.isBlank() ? null : Instant.parse(deadline);
        issuer = identity.issuer();
        subject = access.bootstrapAdminSubject();
        if (until != null) {
            boolean localIssuer = issuer != null
                    && List.of("localhost", "127.0.0.1").contains(URI.create(issuer).getHost());
            if (!identity.enabled() || !identity.allowLocalHttp() || !localIssuer
                    || !List.of("localhost", "127.0.0.1", "::1").contains(bindAddress)
                    || subject == null || subject.isBlank()
                    || until.isAfter(Instant.now().plus(Duration.ofHours(24)))) {
                throw new IllegalStateException("Temporary MFA exception requires a loopback development identity, a named bootstrap administrator, and a deadline within 24 hours");
            }
        }
    }

    public boolean allows(Jwt jwt, Instant now) {
        return matches(jwt) && now.isBefore(until);
    }

    public boolean expiredFor(Jwt jwt, Instant now) {
        return matches(jwt) && !now.isBefore(until);
    }

    private boolean matches(Jwt jwt) {
        return until != null && jwt.getIssuer() != null
                && issuer.equals(jwt.getIssuer().toString()) && subject.equals(jwt.getSubject());
    }
}
