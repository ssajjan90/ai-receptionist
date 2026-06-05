package com.aireceptionist.tenant.adapter.in.web.dto;

import com.aireceptionist.tenant.domain.TenantStatus;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID tenantId,
        String businessName,
        TenantStatus status,
        String tier,
        Instant createdAt
) {
}
