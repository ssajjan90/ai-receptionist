package com.aireceptionist.tenant.port.in;

import java.util.UUID;

/** Story 6.1; {@code status} added in the Epic 6 code review so voice can block non-LIVE tenants (W97). */
public record ResolvedTenantVoiceRoute(
        UUID tenantId,
        String tier,
        String status
) {
}
