package com.aireceptionist.admin.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminAccessLogEntry(
        UUID id,
        UUID adminUserId,
        UUID targetTenantId,
        String eventType,
        String action,
        Instant occurredAt
) {
}
