package com.aireceptionist.tenant.port.in;

import java.util.UUID;

public record ResolvedTenantWhatsAppRoute(
        UUID tenantId,
        String phoneNumberId,
        String ownerPhone
) {
}
