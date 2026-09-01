package com.aireceptionist.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TenantDetailResponse(
        UUID tenantId,
        String businessName,
        String tier,
        String status,
        Instant lastActiveAt,
        long conversationCountLast24h,
        String subscriptionStatus,
        long kbEntryCount,
        long leadCount,
        List<AuditEventSummary> recentAuditEvents
) {
}
