package com.cbs.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(BankDatabaseProvisioner.Settings.class)
public class BankDatabaseProvisioner {
    @ConfigurationProperties("cbs.banking")
    public record Settings(Map<String, Target> databases) {
        public Settings { databases = databases == null ? Map.of() : Map.copyOf(databases); }
    }
    public record Target(String url, String username, String password) {
        // Never print a credential-bearing configuration object.
        @Override public String toString() { return "BankDatabaseTarget[redacted]"; }
    }
    private final Settings settings;
    private final boolean migrationsEnabled;
    public BankDatabaseProvisioner(Settings settings) { this.settings = settings; this.migrationsEnabled = true; }
    @org.springframework.beans.factory.annotation.Autowired
    public BankDatabaseProvisioner(Settings settings, org.springframework.core.env.Environment environment) {
        this.settings = settings;
        this.migrationsEnabled = environment.getProperty("cbs.banking.migrations-enabled", Boolean.class, true);
    }
    private void requireMigrationAuthority() {
        if (!migrationsEnabled) throw new TenantException(503,"DATABASE_MIGRATION_REQUIRED","An operator must run the separate database migration job");
    }

    public void requireApproved(String reference) { target(reference); }

    /** Route only to a database whose ownership has already been reserved for this tenant. */
    public Connection openOwned(UUID tenantId,String reference) {
        Connection connection=null;
        try {
            connection=dataSource(target(reference)).getConnection();
            refuseControlPlane(connection);verifyIdentity(connection,tenantId);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT singleton FROM bank.installation WHERE singleton")) {
                if(!rows.next())throw failure("DATABASE_NOT_READY");
            }
            return connection;
        } catch(Exception exception) {
            if(connection!=null)try{connection.close();}catch(SQLException ignored){}
            if(exception instanceof TenantException problem)throw problem;
            throw failure("DATABASE_UNAVAILABLE");
        }
    }

    public String upgradeOwned(UUID tenantId,String reference) {
        requireMigrationAuthority();
        try(Connection lock=openOwned(tenantId,reference)) {
            try(var statement=lock.createStatement();var rows=statement.executeQuery("SELECT pg_try_advisory_lock(1128420179)")) {
                rows.next();if(!rows.getBoolean(1))throw failure("DATABASE_BUSY");
            }
            var flyway=Flyway.configure().dataSource(dataSource(target(reference))).locations("classpath:db/bank-migration")
                    .defaultSchema("bank").schemas("bank").cleanDisabled(true).validateOnMigrate(true).load();
            flyway.migrate();return flyway.info().current().getVersion().toString();
        }catch(TenantException problem){throw problem;}catch(Exception exception){throw failure("DATABASE_PROVISIONING_FAILED");}
    }

    public String provision(UUID tenantId, String reference) {
        requireMigrationAuthority();
        DriverManagerDataSource dataSource = dataSource(target(reference));
        // The session lock spans ownership reservation and all Flyway connections.
        try (Connection lock = dataSource.getConnection()) {
            try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT pg_try_advisory_lock(1128420179)")) {
                rows.next();
                if (!rows.getBoolean(1)) { throw failure("DATABASE_BUSY"); }
            }
            refuseControlPlane(lock);
            reserveIdentity(lock, tenantId);
            Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/bank-migration")
                    .defaultSchema("bank").schemas("bank").createSchemas(true).cleanDisabled(true)
                    .validateOnMigrate(true).load();
            flyway.migrate();
            verifyIdentity(lock, tenantId);
            return flyway.info().current().getVersion().toString();
        } catch (TenantException exception) {
            throw exception;
        } catch (Exception exception) {
            // Driver and migration exceptions may include connection details. Persist a stable safe code only.
            throw failure("DATABASE_PROVISIONING_FAILED");
        }
    }

    public void verifyRoute(UUID tenantId, String reference) {
        try (Connection connection = dataSource(target(reference)).getConnection()) {
            refuseControlPlane(connection);
            verifyIdentity(connection, tenantId);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT singleton FROM bank.installation WHERE singleton")) {
                if (!rows.next()) { throw failure("DATABASE_NOT_READY"); }
            }
        } catch (TenantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("DATABASE_UNAVAILABLE");
        }
    }

    private static void refuseControlPlane(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='platform')")) {
            rows.next();
            if (rows.getBoolean(1)) { throw failure("CONTROL_PLANE_TARGET_FORBIDDEN"); }
        }
    }

    private static void reserveIdentity(Connection connection, UUID tenantId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS cbs_identity");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS cbs_identity.owner (
                          singleton BOOLEAN PRIMARY KEY CHECK(singleton), tenant_id UUID NOT NULL)
                        """);
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO cbs_identity.owner(singleton,tenant_id) VALUES (true,?) ON CONFLICT DO NOTHING
                    """)) {
                statement.setObject(1, tenantId);
                statement.executeUpdate();
            }
            verifyIdentity(connection, tenantId);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void verifyIdentity(Connection connection, UUID tenantId) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT tenant_id FROM cbs_identity.owner WHERE singleton")) {
            if (!rows.next() || !tenantId.equals(rows.getObject(1, UUID.class))) {
                throw failure("DATABASE_OWNERSHIP_MISMATCH");
            }
        }
    }

    private Target target(String reference) {
        Target target = settings.databases().get(reference);
        if (target == null) { throw new TenantException(422, "DATABASE_REFERENCE_NOT_APPROVED", "Select an operator-approved database reference"); }
        if (target.url() == null || !target.url().matches(
                "jdbc:postgresql://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9_]+(?:\\?sslmode=(?:disable|(?:require|verify-ca|verify-full)(?:&channelBinding=require)?))?")
                || target.username() == null || target.username().isBlank()
                || target.password() == null || target.password().isBlank()) {
            throw new TenantException(503, "DATABASE_CONFIGURATION_INVALID", "The database reference requires operator configuration");
        }
        return target;
    }

    private static DriverManagerDataSource dataSource(Target target) {
        DriverManagerDataSource source = new DriverManagerDataSource(target.url(), target.username(), target.password());
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("options", "-c statement_timeout=30000 -c lock_timeout=5000");
        source.setConnectionProperties(properties);
        return source;
    }

    private static TenantException failure(String code) {
        return new TenantException(503, code, "Bank database operation requires operator attention");
    }
}
