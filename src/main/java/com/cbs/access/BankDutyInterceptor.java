package com.cbs.access;

import com.cbs.tenancy.TenantException;
import com.cbs.tenancy.TenantModels.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link BankDutyRoutes} to every bank-scoped administrator route before the controller runs.
 * Banks that do not enforce duties are unaffected. Routes on the branch, lending and payment workspaces
 * ({@code /api/v1/banks/...}) keep their own grant models.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
public class BankDutyInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    /** Name of the bank path variable in a matched route pattern, e.g. {bankId}. */
    private static final java.util.regex.Pattern BANK = java.util.regex.Pattern.compile("^/api/v1/admin/tenants/\\{([^}/]+)}");
    private final BankDuties duties;

    public BankDutyInterceptor(BankDuties duties) { this.duties = duties; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/admin/tenants/*", "/api/v1/admin/tenants/*/**");
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern)) return true;
        var match = BANK.matcher(pattern);
        if (!match.find()) return true; // not a bank-scoped route
        // Fail closed from here: read the bank exactly as Spring binds it for the controller (decoded), never from
        // the raw URI, so an encoded or decorated identifier cannot skip the check.
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token))
            throw new TenantException(403, "ACCESS_DENIED", "Platform administrator access is required");
        UUID bank;
        try {
            @SuppressWarnings("unchecked")
            var variables = (java.util.Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            bank = UUID.fromString(variables.get(match.group(1)));
        } catch (RuntimeException invalid) {
            throw new TenantException(400, "INVALID_REQUEST", "Request fields or required headers are invalid");
        }
        BankDutyRoutes.Rule rule;
        try { rule = BankDutyRoutes.rule(pattern); }
        catch (IllegalStateException unclassified) {
            // Fail closed: a new route nobody classified must not bypass enforced duties.
            if (duties.enforced(bank)) throw new TenantException(403, "BANK_DUTY_ROUTE_UNCLASSIFIED", "This route has no bank duty assigned");
            return true;
        }
        if (rule == null) return true;
        Actor actor = new Actor(token.getToken().getIssuer().toString(), token.getToken().getSubject(), true, null);
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        switch (rule.kind()) {
            case PLATFORM, SELF, LENDING -> { }
            // During lockout recovery any administrator must still be able to read the bank to propose switching enforcement off.
            case ANY -> { if (read) { if (!duties.lockedOut(bank)) duties.readAny(actor, bank, java.util.Set.of()); }
                          else duties.require(actor, bank, rule.write(), false); }
            case DUTY -> { if (read) duties.readAny(actor, bank, rule.read()); else duties.require(actor, bank, rule.write(), false); }
        }
        return true;
    }
}
