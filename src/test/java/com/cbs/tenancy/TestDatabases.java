package com.cbs.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestDatabases {
    record Database(String name, String url, String password) { }
    private static final Map<String, Database> DATABASES = new LinkedHashMap<>();
    private TestDatabases() { }

    static synchronized void configure(DynamicPropertyRegistry registry) {
        if (DATABASES.isEmpty()) {
            String prefix = "cbs_m01_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = adminConnection()) {
                for (String suffix : new String[] {"control", "a", "b", "failure", "recovery"}) {
                    String name = prefix + "_" + suffix;
                    String password = UUID.randomUUID().toString().replace("-", "");
                    try (var statement = admin.createStatement()) {
                        // Identifiers and passwords are generated hex/alphanumeric fixtures, never user input.
                        statement.execute("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'");
                        statement.execute("CREATE DATABASE " + name + " OWNER " + name);
                        statement.execute("REVOKE CONNECT ON DATABASE " + name + " FROM PUBLIC");
                        statement.execute("GRANT CONNECT ON DATABASE " + name + " TO " + name);
                    }
                    String source = System.getenv("CBS_DB_URL");
                    String url = source.substring(0, source.lastIndexOf('/') + 1) + name;
                    DATABASES.put(suffix, new Database(name, url, password));
                }
            } catch (SQLException exception) { throw new IllegalStateException("Cannot create isolated Module 01 test databases", exception); }
        }
        Database control = DATABASES.get("control");
        registry.add("spring.datasource.url", control::url);
        registry.add("spring.datasource.username", control::name);
        registry.add("spring.datasource.password", control::password);
        bind(registry, "bank-a", "a");
        bind(registry, "bank-b", "b");
        bind(registry, "bank-b-alias", "b");
        bind(registry, "migration-failure", "failure");
        bind(registry, "control-target", "control");
        bind(registry, "recovery-one", "recovery");
        for (String reference : new String[] {"registration-one", "race-one", "code-first", "code-second",
                "binding-one", "invalid-first", "race-state", "audit-only", "entity-one","branch-bank-a","branch-bank-b"}) {
            bind(registry, reference, "a");
        }
    }

    private static void bind(DynamicPropertyRegistry registry, String reference, String database) {
        Database target = DATABASES.get(database);
        String prefix = "cbs.banking.databases." + reference;
        registry.add(prefix + ".url", target::url);
        registry.add(prefix + ".username", target::name);
        registry.add(prefix + ".password", target::password);
    }

    static Connection bank(String key) throws SQLException {
        Database target = DATABASES.get(key);
        return DriverManager.getConnection(target.url(), target.name(), target.password());
    }

    static Connection crossBankConnection() throws SQLException {
        Database a = DATABASES.get("a");
        return DriverManager.getConnection(DATABASES.get("b").url(), a.name(), a.password());
    }

    static void close() throws SQLException {
        try (Connection admin = adminConnection(); var statement = admin.createStatement()) {
            for (Database target : DATABASES.values()) {
                if (!target.name().matches("cbs_m01_[0-9a-f]{32}_(control|a|b|failure|recovery)")) { throw new IllegalStateException("Unsafe fixture name"); }
                statement.execute("DROP DATABASE IF EXISTS " + target.name() + " WITH (FORCE)");
                statement.execute("DROP ROLE IF EXISTS " + target.name());
            }
        }
    }

    private static Connection adminConnection() throws SQLException {
        String user = System.getenv().getOrDefault("CBS_DB_USER", "cbs_control");
        return DriverManager.getConnection(System.getenv("CBS_DB_URL"), user, System.getenv("CBS_DB_PASSWORD"));
    }
}
