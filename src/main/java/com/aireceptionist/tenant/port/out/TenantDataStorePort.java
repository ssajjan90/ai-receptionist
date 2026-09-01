package com.aireceptionist.tenant.port.out;

import com.aireceptionist.tenant.port.in.TenantDataExport;

import java.util.UUID;

public interface TenantDataStorePort {

    void eraseTenantData(UUID tenantId);

    TenantDataExport exportTenantData(UUID tenantId);
}
