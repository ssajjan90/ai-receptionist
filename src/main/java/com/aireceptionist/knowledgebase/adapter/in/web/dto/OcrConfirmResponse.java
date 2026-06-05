package com.aireceptionist.knowledgebase.adapter.in.web.dto;

import java.util.UUID;

public record OcrConfirmResponse(
        UUID tenantId,
        int entriesSaved,
        String message
) {
}
