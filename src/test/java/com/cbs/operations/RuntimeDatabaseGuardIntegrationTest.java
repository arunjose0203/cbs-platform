package com.cbs.operations;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CBS_INTEGRATION_TESTS",matches="true")
class RuntimeDatabaseGuardIntegrationTest {
    @Test void runtimeVerifiesPreparedBankWithoutMigrationPrivileges() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String db="cbs_prepared_"+suffix, role="cbs_readonly_"+suffix, secret=UUID.randomUUID().toString();
        String source=System.getenv("CBS_DB_URL"),user=System.getenv("CBS_DB_USER"),password=System.getenv("CBS_DB_PASSWORD");
        String url=source.substring(0,source.lastIndexOf('/')+1)+db;
        var bankId=UUID.randomUUID();
        try(var admin=DriverManager.getConnection(source,user,password);var s=admin.createStatement()) {
            s.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+secret+"'");
            try {
                s.execute("CREATE DATABASE "+db);
                try {
                    var owner=new com.cbs.tenancy.BankDatabaseProvisioner(new com.cbs.tenancy.BankDatabaseProvisioner.Settings(java.util.Map.of("bank",new com.cbs.tenancy.BankDatabaseProvisioner.Target(url,user,password))));
                    var runtime=new com.cbs.tenancy.BankDatabaseProvisioner(new com.cbs.tenancy.BankDatabaseProvisioner.Settings(java.util.Map.of("bank",new com.cbs.tenancy.BankDatabaseProvisioner.Target(url,role,secret))),new org.springframework.mock.env.MockEnvironment().withProperty("cbs.banking.migrations-enabled","false"));
                    assertThatThrownBy(()->runtime.provision(bankId,"bank")).hasMessageContaining("separate migration job");
                    String version=owner.provision(bankId,"bank");
                    try(var c=DriverManager.getConnection(url,user,password);var sql=c.createStatement()) {
                        sql.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA bank,cbs_identity TO "+role+"; GRANT SELECT ON ALL TABLES IN SCHEMA bank,cbs_identity TO "+role);
                        assertThat(runtime.provision(bankId,"bank")).isEqualTo(version);
                        assertThat(runtime.upgradeOwned(bankId,"bank")).isEqualTo(version);
                        assertThatThrownBy(()->runtime.provision(UUID.randomUUID(),"bank")).hasMessageContaining("separate migration job");
                        sql.execute("UPDATE bank.flyway_schema_history SET checksum=0 WHERE version='1'");
                        assertThatThrownBy(()->runtime.upgradeOwned(bankId,"bank")).hasMessageContaining("separate migration job");
                    }
                } finally {s.execute("DROP DATABASE "+db+" WITH (FORCE)");}
            } finally {s.execute("DROP ROLE "+role);}
        }
    }
    @Test void rejectsOwnersAndCreateAuthorityButAcceptsRestrictedRuntime() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String db="cbs_privilege_"+suffix, role="cbs_runtime_"+suffix;
        String source=System.getenv("CBS_DB_URL"), user=System.getenv("CBS_DB_USER"), password=System.getenv("CBS_DB_PASSWORD");
        String url=source.substring(0,source.lastIndexOf('/')+1)+db;
        try(var admin=DriverManager.getConnection(source,user,password);var s=admin.createStatement()) {
            s.execute("CREATE ROLE "+role);
            try {
                s.execute("CREATE DATABASE "+db);
                try(var c=DriverManager.getConnection(url,user,password);var sql=c.createStatement()) {
                    sql.execute("CREATE SCHEMA platform; CREATE TABLE platform.fixture(id integer); REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA platform TO "+role);
                    assertThatThrownBy(()->RuntimeDatabaseGuard.verify(c)).hasMessageContaining("must not own");
                    sql.execute("SET ROLE "+role);
                    assertThatCode(()->RuntimeDatabaseGuard.verify(c)).doesNotThrowAnyException();
                    sql.execute("RESET ROLE; GRANT CREATE ON SCHEMA platform TO "+role+"; SET ROLE "+role);
                    assertThatThrownBy(()->RuntimeDatabaseGuard.verify(c)).hasMessageContaining("must not own");
                    sql.execute("RESET ROLE; REVOKE CREATE ON SCHEMA platform FROM "+role+"; ALTER TABLE platform.fixture OWNER TO "+role+"; SET ROLE "+role);
                    assertThatThrownBy(()->RuntimeDatabaseGuard.verify(c)).hasMessageContaining("must not own");
                } finally {s.execute("DROP DATABASE "+db+" WITH (FORCE)");}
            } finally {s.execute("DROP ROLE "+role);}
        }
    }
}
