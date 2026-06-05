package com.aireceptionist.tenant.port.in;

public record ConnectTenantWhatsAppCommand(
        String wabaId,
        String phoneNumberId,
        String displayPhoneNumber
) {
}
