package com.cbs.operations;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MigrationMainTest {
    @Test void requiresVerifiedProductionTransportAndBankIdentity() {
        var id=UUID.randomUUID();
        assertThatCode(()->MigrationMain.validate(new MigrationMain.Database("jdbc:postgresql://db.example/bank?sslmode=verify-full","owner","/secret",id),false,true)).doesNotThrowAnyException();
        for(var url:java.util.List.of("jdbc:postgresql://db/bank?sslmode=require","jdbc:postgresql://db/bank?sslmode=verify-full&password=secret","jdbc:postgresql://db/bank?sslmode=verify-full&sslmode=disable"))
            assertThatThrownBy(()->MigrationMain.validate(new MigrationMain.Database(url,"owner","/secret",id),false,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MigrationMain.validate(new MigrationMain.Database("jdbc:postgresql://db/bank?sslmode=verify-full","owner","/secret",null),false,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void optionsSelectCanaryBanksStopOnFailureAndReport() {
        var o=MigrationMain.options(new String[]{"plan.json","--local-test","--only","bank-a,bank-b","--stop-on-failure","--report","r.json"});
        assertThat(o.local()).isTrue();assertThat(o.only()).containsExactlyInAnyOrder("bank-a","bank-b");assertThat(o.stopOnFailure()).isTrue();assertThat(o.report()).hasToString("r.json");
        assertThat(MigrationMain.options(new String[]{"plan.json"}).only()).isEmpty();
        for(var bad:java.util.List.of(new String[]{},new String[]{"plan.json","--only"},new String[]{"plan.json","--only","a,,b"},new String[]{"plan.json","--unknown"}))
            assertThatThrownBy(()->MigrationMain.options(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(com.cbs.tenancy.BankDatabaseProvisioner.expectedBankVersion()).matches("[0-9]+");
    }
    @Test void localTestCannotContactRemoteDatabase() {
        assertThatCode(()->MigrationMain.validate(new MigrationMain.Database("jdbc:postgresql://127.0.0.1:55433/drill","owner","/secret",null),true,false)).doesNotThrowAnyException();
        assertThatThrownBy(()->MigrationMain.validate(new MigrationMain.Database("jdbc:postgresql://remote:5432/drill","owner","/secret",null),true,false)).isInstanceOf(IllegalArgumentException.class);
    }
}
