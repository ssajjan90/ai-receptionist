package com.aireceptionist.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuditEventSummary(
        UUID id,
        String eventType,
        BigDecimal confidence,
        Instant occurredAt
) {
}
