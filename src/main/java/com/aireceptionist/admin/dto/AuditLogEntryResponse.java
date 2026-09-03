package com.aireceptionist.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Story 5.6 (AC1). */
public record AuditLogEntryResponse(
        UUID id,
        UUID tenantId,
        String eventType,
        BigDecimal confidence,
        String messageHash,
        Instant occurredAt
) {
}
