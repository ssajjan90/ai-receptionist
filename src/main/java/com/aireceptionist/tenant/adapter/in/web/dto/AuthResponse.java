package com.aireceptionist.tenant.adapter.in.web.dto;

import java.util.UUID;

public record AuthResponse(
        String jwt,
        UUID tenantId,
        String role,
        String tier
) {
}
