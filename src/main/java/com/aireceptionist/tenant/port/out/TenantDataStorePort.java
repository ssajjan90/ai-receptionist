package com.aireceptionist.tenant.port.out;

import com.aireceptionist.tenant.domain.TenantDataExport;

import java.util.UUID;

public interface TenantDataStorePort {

    void eraseTenantData(UUID tenantId);

    TenantDataExport exportTenantData(UUID tenantId);
}
