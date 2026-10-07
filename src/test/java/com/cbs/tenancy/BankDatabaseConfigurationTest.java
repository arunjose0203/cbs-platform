package com.cbs.tenancy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankDatabaseConfigurationTest {
    private BankDatabaseProvisioner provisioner(String url) {
        return new BankDatabaseProvisioner(new BankDatabaseProvisioner.Settings(Map.of(
                "first-bank", new BankDatabaseProvisioner.Target(url, "bank_owner", "test-only"))));
    }

    @Test void acceptsNeonTlsAndRequiredChannelBindingWithDefaultOrExplicitPort() {
        for (String port : new String[]{"", ":5432"}) {
            String url = "jdbc:postgresql://ep-example.us-east-2.aws.neon.tech" + port
                    + "/cbs_first_bank?sslmode=require&channelBinding=require";
            assertThatCode(() -> provisioner(url).requireApproved("first-bank")).doesNotThrowAnyException();
        }
        assertThatCode(() -> provisioner("jdbc:postgresql://localhost:55433/test_bank")
                .requireApproved("first-bank")).doesNotThrowAnyException();
    }

    @Test void rejectsCredentialsAndUnapprovedDriverOptionsInUrls() {
        for (String url : new String[]{
                "jdbc:postgresql://owner:secret@localhost:5432/bank",
                "jdbc:postgresql://localhost:5432/bank?sslmode=require&password=secret",
                "jdbc:postgresql://localhost:5432/bank?sslmode=require&socketFactory=unsafe.Factory",
                "jdbc:postgresql://localhost:5432/bank?sslmode=disable&channelBinding=require",
                "jdbc:postgresql://localhost:5432/bank?sslmode=require&channelBinding=disable"}) {
            assertThatThrownBy(() -> provisioner(url).requireApproved("first-bank"))
                    .as(url).isInstanceOf(TenantException.class)
                    .extracting(e -> ((TenantException) e).code()).isEqualTo("DATABASE_CONFIGURATION_INVALID");
        }
    }
}
