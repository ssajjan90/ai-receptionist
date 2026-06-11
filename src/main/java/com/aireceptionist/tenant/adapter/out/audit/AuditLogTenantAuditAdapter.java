package com.aireceptionist.tenant.adapter.out.audit;

import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogWriter;
import com.aireceptionist.tenant.port.out.TenantAuditPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class AuditLogTenantAuditAdapter implements TenantAuditPort {

    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public AuditLogTenantAuditAdapter(AuditLogWriter auditLogWriter, Clock clock) {
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    public void recordTenantDataErased(UUID tenantId) {
        auditLogWriter.save(new AuditLogEntry(
                UUID.randomUUID(),
                tenantId,
                "TENANT_DATA_ERASED",
                null,
                null,
                Instant.now(clock)
        ));
    }

    @Override
    public void recordTenantDataExported(UUID tenantId) {
        auditLogWriter.save(new AuditLogEntry(
                UUID.randomUUID(),
                tenantId,
                "TENANT_DATA_EXPORTED",
                BigDecimal.ONE,
                null,
                Instant.now(clock)
        ));
    }
}
