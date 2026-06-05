package com.aireceptionist.tenant.port.in;

import java.util.UUID;

public interface ConnectTenantWhatsAppUseCase {

    ConnectedTenantWhatsApp connectWhatsApp(UUID tenantId, ConnectTenantWhatsAppCommand command);
}
