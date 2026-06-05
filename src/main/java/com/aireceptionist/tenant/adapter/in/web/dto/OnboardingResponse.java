package com.aireceptionist.tenant.adapter.in.web.dto;

import com.aireceptionist.tenant.domain.TenantStatus;

import java.util.UUID;

public record OnboardingResponse(
        UUID tenantId,
        TenantStatus status,
        int kbEntriesCreated,
        String message
) {
}
