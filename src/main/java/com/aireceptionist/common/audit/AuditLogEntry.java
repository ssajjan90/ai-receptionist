package com.aireceptionist.common.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(
        UUID tenantId,
        String eventType,
        BigDecimal confidence,
        String messageHash,
        Instant occurredAt
) {
}
