package com.aireceptionist.common.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    public static final String DEFAULT_TENANT = "DEFAULT";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getCurrentTenant();
        org.slf4j.LoggerFactory.getLogger(TenantIdentifierResolver.class).error(
                "DEBUG_PROBE_RESOLVE tenantId={} thread={}", tenantId, Thread.currentThread().getName(),
                new Exception("stack"));
        return tenantId != null ? tenantId : DEFAULT_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
