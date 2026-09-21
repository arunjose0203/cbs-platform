package com.cbs.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named = "CBS_INTEGRATION_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseIntegrationTest {
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @Test
    void migrationAppliesAndCanBeRunAgainWithoutChanges() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'platform'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void readinessIncludesAWorkingDatabase() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
