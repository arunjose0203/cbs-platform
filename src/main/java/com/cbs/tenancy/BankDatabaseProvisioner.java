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
    private String verifyPrepared(UUID tenantId, String reference) {
        try (var connection = openOwned(tenantId, reference)) {
            var flyway = Flyway.configure().dataSource(dataSource(target(reference)))
                    .locations("classpath:db/bank-migration").defaultSchema("bank").schemas("bank")
                    .createSchemas(false).cleanDisabled(true).ignoreMigrationPatterns(new String[0]).load();
            // Read-only validation lets an administrator activate an operator-migrated bank.
            // The runtime never creates schemas, changes history or invokes migrate().
            flyway.validate();
            var info = flyway.info();
            if (info.current() == null || info.pending().length > 0)
                throw new IllegalStateException();
            return info.current().getVersion().toString();
        } catch (Exception failure) {
            throw new TenantException(503,"DATABASE_MIGRATION_REQUIRED","An operator must prepare and verify the bank database with the separate migration job");
        }
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
            // P-08: where the runtime never migrates (production), a bank left behind by a failed or partial
            // rolling upgrade is refused instead of meeting SQL written for a newer schema.
            if(!migrationsEnabled)requireCurrentSchema(connection,reference);
            return connection;
        } catch(Exception exception) {
            if(connection!=null)try{connection.close();}catch(SQLException ignored){}
            if(exception instanceof TenantException problem)throw problem;
            throw failure("DATABASE_UNAVAILABLE");
        }
    }

    /** Highest bank migration version shipped in this build. */
    public static String expectedBankVersion(){return EXPECTED;}
    private static final String EXPECTED=latestBankMigration();
    private static String latestBankMigration(){
        try{
            int max=0;
            for(var r:new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:db/bank-migration/V*__*.sql")){
                var m=java.util.regex.Pattern.compile("V([0-9]+)__.*").matcher(java.util.Objects.requireNonNull(r.getFilename()));if(m.matches())max=Math.max(max,Integer.parseInt(m.group(1)));
            }
            return Integer.toString(max);
        }catch(java.io.IOException e){throw new IllegalStateException("Bank migrations unreadable",e);}
    }
    /** Banks confirmed at the expected version; a bank found behind is checked again on every connection. */
    private final java.util.Map<String,Long> current=new java.util.concurrent.ConcurrentHashMap<>();
    private void requireCurrentSchema(Connection connection,String reference)throws SQLException{
        Long until=current.get(reference);if(until!=null&&until>System.currentTimeMillis())return;
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version FROM bank.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){
            if(!rows.next()||!EXPECTED.equals(rows.getString(1))){current.remove(reference);throw failure("BANK_SCHEMA_UPGRADE_REQUIRED");}
        }
        current.put(reference,System.currentTimeMillis()+60_000);
    }

    public String upgradeOwned(UUID tenantId,String reference) {
        if (!migrationsEnabled) return verifyPrepared(tenantId, reference);
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
        if (!migrationsEnabled) return verifyPrepared(tenantId, reference);
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
