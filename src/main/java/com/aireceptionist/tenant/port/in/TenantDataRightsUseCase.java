package com.aireceptionist.tenant.port.in;


import java.util.UUID;

public interface TenantDataRightsUseCase {

    void eraseTenantData(UUID tenantId);

    TenantDataExport exportTenantData(UUID tenantId);
}
