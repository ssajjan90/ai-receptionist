package com.aireceptionist.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminDashboardResponse(
        UUID tenantId,
        String businessName,
        String tier,
        String status,
        Instant lastActiveAt,
        long conversationCountLast24h,
        String subscriptionStatus
) {
}
