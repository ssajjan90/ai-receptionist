package com.aireceptionist.tenant.port.out;

import java.util.UUID;

public interface TenantAuditPort {

    void recordTenantDataErased(UUID tenantId);

    void recordTenantDataExported(UUID tenantId);
}
