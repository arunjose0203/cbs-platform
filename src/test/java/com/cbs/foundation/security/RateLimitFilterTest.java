package com.cbs.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private MockHttpServletResponse call(RateLimitFilter filter, String address, String path, int downstream)
            throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override protected void service(jakarta.servlet.http.HttpServletRequest q,
                    jakarta.servlet.http.HttpServletResponse r) { r.setStatus(downstream); }
        }));
        return response;
    }

    @Test void throttlesPerClientAndRefillsOverTime() throws Exception {
        var filter = new RateLimitFilter(new RateLimitFilter.Settings(true, 3, 10, 100), clock::get);
        for (int i = 0; i < 3; i++) { assertThat(call(filter, "10.0.0.1", "/api/v1/x", 200).getStatus()).isEqualTo(200); }
        var limited = call(filter, "10.0.0.1", "/api/v1/x", 200);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("20");
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(call(filter, "10.0.0.2", "/api/v1/x", 200).getStatus()).isEqualTo(200);
        clock.addAndGet(20_000_000_000L);
        assertThat(call(filter, "10.0.0.1", "/api/v1/x", 200).getStatus()).isEqualTo(200);
        assertThat(call(filter, "10.0.0.1", "/api/v1/x", 200).getStatus()).isEqualTo(429);
    }

    @Test void authenticationFailuresUseTheirOwnTighterBudget() throws Exception {
        var filter = new RateLimitFilter(new RateLimitFilter.Settings(true, 100, 2, 100), clock::get);
        assertThat(call(filter, "10.0.0.9", "/api/v1/x", 401).getStatus()).isEqualTo(401);
        assertThat(call(filter, "10.0.0.9", "/api/v1/x", 401).getStatus()).isEqualTo(401);
        var blocked = call(filter, "10.0.0.9", "/api/v1/x", 200);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("30");
        assertThat(call(filter, "10.0.0.10", "/api/v1/x", 200).getStatus()).isEqualTo(200);
    }

    @Test void healthProbesAndDisabledLimitsAreNeverThrottled() throws Exception {
        var filter = new RateLimitFilter(new RateLimitFilter.Settings(true, 1, 1, 100), clock::get);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "10.0.0.3", "/actuator/health/readiness", 200).getStatus()).isEqualTo(200);
        }
        var off = new RateLimitFilter(new RateLimitFilter.Settings(false, 1, 1, 100), clock::get);
        for (int i = 0; i < 5; i++) { assertThat(call(off, "10.0.0.3", "/api/v1/x", 401).getStatus()).isEqualTo(401); }
    }

    @Test void trackedClientsStayBounded() throws Exception {
        var filter = new RateLimitFilter(new RateLimitFilter.Settings(true, 5, 5, 50), clock::get);
        for (int i = 0; i < 500; i++) { call(filter, "10.1." + (i / 250) + "." + (i % 250), "/api/v1/x", 200); }
        assertThat(filter.trackedClients()).isLessThanOrEqualTo(50);
    }

    @Test void defaultsAndValidation() {
        var defaults = new RateLimitFilter.Settings(null, null, null, null);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.requestsPerMinute()).isEqualTo(600);
        assertThat(defaults.authenticationFailuresPerMinute()).isEqualTo(20);
        assertThatThrownBy(() -> new RateLimitFilter.Settings(true, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
