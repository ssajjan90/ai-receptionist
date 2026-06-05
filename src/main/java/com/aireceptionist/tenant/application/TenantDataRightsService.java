package com.aireceptionist.tenant.application;

import com.aireceptionist.tenant.domain.TenantDataExport;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import com.aireceptionist.tenant.port.out.TenantAuditPort;
import com.aireceptionist.tenant.port.out.TenantDataStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service("tenantDataRightsService")
public class TenantDataRightsService implements TenantDataRightsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantDataRightsService.class);

    private final TenantDataStorePort tenantDataStorePort;
    private final TenantAuditPort tenantAuditPort;

    public TenantDataRightsService(TenantDataStorePort tenantDataStorePort, TenantAuditPort tenantAuditPort) {
        this.tenantDataStorePort = tenantDataStorePort;
        this.tenantAuditPort = tenantAuditPort;
    }

    @Override
    public void eraseTenantData(UUID tenantId) {
        tenantDataStorePort.eraseTenantData(tenantId);
        tenantAuditPort.recordTenantDataErased(tenantId);
        log.info("Erased tenant data for tenant {}", tenantId);
    }

    @Override
    public TenantDataExport exportTenantData(UUID tenantId) {
        TenantDataExport export = tenantDataStorePort.exportTenantData(tenantId);
        tenantAuditPort.recordTenantDataExported(tenantId);
        log.info("Exported tenant data for tenant {}", tenantId);
        return export;
    }
}
