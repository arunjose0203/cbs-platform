package com.cbs.tenancy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cbs.tenancy.enabled", havingValue = "true", matchIfMissing = true)
public class TenantModuleConfiguration {
    @Bean
    TenantRepository tenantRepository(JdbcTemplate jdbc) { return new TenantRepository(jdbc); }
    @Bean
    TenantService tenantService(TenantRepository repository, PlatformTransactionManager transactions,
            BankDatabaseProvisioner provisioner) {
        return new TenantService(repository, new TransactionTemplate(transactions), provisioner);
    }
}
