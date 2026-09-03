package com.aireceptionist.tenant.port.in;

import java.util.UUID;

public record ResolvedTenantVoiceRoute(
        UUID tenantId,
        String tier
) {
}
