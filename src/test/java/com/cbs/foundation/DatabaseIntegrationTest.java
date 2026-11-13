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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import java.sql.DriverManager;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "CBS_INTEGRATION_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrationTest {
    private static final String DATABASE = "cbs_foundation_" + UUID.randomUUID().toString().replace("-", "");
    private static String url;
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        String source=System.getenv("CBS_DB_URL");
        String user=System.getenv().getOrDefault("CBS_DB_USER","cbs_control");
        String password=System.getenv("CBS_DB_PASSWORD");
        if(url==null){
            try(var c=DriverManager.getConnection(source,user,password);var s=c.createStatement()){
                s.execute("CREATE DATABASE "+DATABASE);
            }
            url=source.substring(0,source.lastIndexOf('/')+1)+DATABASE;
        }
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->user);
        registry.add("spring.datasource.password",()->password);
    }
    @AfterAll
    void removeIsolatedDatabase() throws Exception {
        if(!DATABASE.matches("cbs_foundation_[0-9a-f]{32}"))throw new IllegalStateException("Unsafe fixture name");
        if(jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource pool)pool.close();
        try(var c=DriverManager.getConnection(System.getenv("CBS_DB_URL"),System.getenv().getOrDefault("CBS_DB_USER","cbs_control"),System.getenv("CBS_DB_PASSWORD"));var s=c.createStatement()){
            s.execute("DROP DATABASE "+DATABASE+" WITH (FORCE)");
        }
    }
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @Test
    void migrationAppliesAndCanBeRunAgainWithoutChanges() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("55");
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
