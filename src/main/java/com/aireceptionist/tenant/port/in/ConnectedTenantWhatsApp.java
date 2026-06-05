package com.aireceptionist.tenant.port.in;

import java.util.UUID;

public record ConnectedTenantWhatsApp(
        UUID tenantId,
        String status,
        String phoneNumberId,
        String message
) {
}
