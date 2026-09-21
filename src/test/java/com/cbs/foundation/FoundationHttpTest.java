package com.cbs.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cbs.foundation.web.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// Deliberately excludes the database. DatabaseIntegrationTest exercises real migrations.
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false",
        "cbs.tenancy.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class FoundationHttpTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void livenessIsPublicAndDoesNotExposeComponents() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void callerCannotAuthorizeItselfUsingTenantHeaders() throws Exception {
        mvc.perform(get("/api/v1/tenants").header("X-Tenant-ID", "bank-a"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void writesAndOtherActuatorRoutesRemainClosed() throws Exception {
        mvc.perform(post("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
    }

    @Test
    void preservesSafeCorrelationIdOnRejectedRequestsAndClearsLoggingContext() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/tenants").header(CorrelationIdFilter.HEADER, id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIdFilter.HEADER, id));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesUnsafeCorrelationId() throws Exception {
        String value = mvc.perform(get("/actuator/health/liveness")
                        .header(CorrelationIdFilter.HEADER, "untrusted value <script>"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(value).isNotNull();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }
}
