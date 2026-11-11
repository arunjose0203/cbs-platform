package com.cbs.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class IdentityUrlPolicyTest {
    private IdentityConfiguration.Settings settings(String issuer, String keys, boolean local) {
        return new IdentityConfiguration.Settings(true, issuer, keys, "cbs-api", local);
    }

    @Test void httpsIsTheDefaultAndDoesNotExpandLocalCsp() {
        assertThat(settings("https://id.example/realm", "https://id.example/keys", false)
                .localConnectOrigin()).isEmpty();
        assertThatThrownBy(() -> settings("http://127.0.0.1:8180/realm", "https://id.example/keys", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void explicitLocalModeAllowsOnlyTheExactLoopbackOrigin() {
        assertThat(settings("http://127.0.0.1:8180/realms/cbs", "http://127.0.0.1:8180/keys", true)
                .localConnectOrigin()).isEqualTo(" http://127.0.0.1:8180");
        for (String invalid : new String[]{"http://id.example/realm", "http://127.0.0.1.evil.test/realm",
                "http://127.0.0.2/realm", "http://user@localhost/realm", "http://localhost/realm#fragment",
                "http://localhost/realm?query=x", "file:///keys"}) {
            assertThatThrownBy(() -> settings(invalid, "https://id.example/keys", true))
                    .as(invalid).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> settings("https://id.example/realm", invalid, true))
                    .as(invalid).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void localIdentityModeCannotExposeCbsOnAnExternalInterface() {
        var local = settings("http://127.0.0.1:8180/realm", "http://127.0.0.1:8180/keys", true);
        assertThatThrownBy(() -> new SecurityConfiguration().securityFilterChain(null, local, null, null, null, "0.0.0.0"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bind to loopback");
    }
}
