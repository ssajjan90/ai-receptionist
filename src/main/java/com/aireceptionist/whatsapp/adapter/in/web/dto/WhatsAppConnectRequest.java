package com.aireceptionist.whatsapp.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record WhatsAppConnectRequest(
        @NotBlank String wabaId,
        @NotBlank String phoneNumberId,
        @NotBlank String displayPhoneNumber
) {
}
