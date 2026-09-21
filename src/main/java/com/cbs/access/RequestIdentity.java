package com.cbs.access;

import com.cbs.tenancy.TenantModels.Actor;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class RequestIdentity {
    private RequestIdentity() { }
    public static Actor actor(JwtAuthenticationToken token) {
        return new Actor(token.getToken().getIssuer().toString(),token.getToken().getSubject(),
                token.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN")),
                UUID.fromString(MDC.get("correlationId")));
    }
}
