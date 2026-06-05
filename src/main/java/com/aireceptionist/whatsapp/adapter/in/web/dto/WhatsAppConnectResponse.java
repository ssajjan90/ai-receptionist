package com.aireceptionist.whatsapp.adapter.in.web.dto;

import java.util.UUID;

public record WhatsAppConnectResponse(
        UUID tenantId,
        String status,
        String phoneNumberId,
        String message
) {
}
