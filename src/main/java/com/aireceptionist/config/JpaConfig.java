package com.aireceptionist.config;

import com.aireceptionist.common.multitenancy.TenantConnectionProvider;
import com.aireceptionist.common.multitenancy.TenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class JpaConfig implements HibernatePropertiesCustomizer {

    private final TenantIdentifierResolver tenantIdentifierResolver;
    private final TenantConnectionProvider tenantConnectionProvider;

    public JpaConfig(TenantIdentifierResolver tenantIdentifierResolver,
                     TenantConnectionProvider tenantConnectionProvider) {
        this.tenantIdentifierResolver = tenantIdentifierResolver;
        this.tenantConnectionProvider = tenantConnectionProvider;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.multiTenancy", "DATABASE");
        hibernateProperties.put("hibernate.multi_tenant_connection_provider", tenantConnectionProvider);
        hibernateProperties.put("hibernate.tenant_identifier_resolver", tenantIdentifierResolver);
    }
}
