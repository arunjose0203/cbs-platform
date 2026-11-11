package com.cbs.foundation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// The throttle sits inside the security chain: guessing tokens is cut off, health probes are not.
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "cbs.tenancy.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState",
        "cbs.rate-limit.requests-per-minute=50",
        "cbs.rate-limit.authentication-failures-per-minute=3"
})
@AutoConfigureMockMvc
class RateLimitHttpTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void repeatedAuthenticationFailuresAreThrottledPerClient() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/tenants").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/tenants").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(get("/api/v1/tenants").with(r -> { r.setRemoteAddr("203.0.113.8"); return r; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/liveness").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                .andExpect(status().isOk());
    }
}
