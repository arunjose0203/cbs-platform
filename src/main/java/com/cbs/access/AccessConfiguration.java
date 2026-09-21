package com.cbs.access;

import com.cbs.foundation.security.IdentityConfiguration;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AccessConfiguration.Settings.class)
public class AccessConfiguration {
    @ConfigurationProperties("cbs.access")
    public record Settings(Duration idleTimeout, Duration maximumSessionAge, Duration maximumTokenAge,
            Duration administratorMfaAge, String bootstrapAdminSubject) {
        public Settings {
            idleTimeout = checked(idleTimeout, Duration.ofMinutes(15));
            maximumSessionAge = checked(maximumSessionAge, Duration.ofHours(8));
            maximumTokenAge = checked(maximumTokenAge, Duration.ofMinutes(30));
            administratorMfaAge = checked(administratorMfaAge, Duration.ofMinutes(30));
        }
        private static Duration checked(Duration value, Duration fallback) {
            value = value == null ? fallback : value;
            if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalStateException("Access time limits must be between zero and one day");
            }
            return value;
        }
    }
    @Bean
    @ConditionalOnProperty(name="cbs.tenancy.enabled",havingValue="true",matchIfMissing=true)
    ApplicationRunner bootstrapAdministrator(AccessService access, Settings settings, IdentityConfiguration.Settings identity) {
        return args -> {
            if (identity.enabled() && settings.bootstrapAdminSubject() != null && !settings.bootstrapAdminSubject().isBlank()) {
                access.bootstrap(identity.issuer(), settings.bootstrapAdminSubject());
            }
        };
    }
}
