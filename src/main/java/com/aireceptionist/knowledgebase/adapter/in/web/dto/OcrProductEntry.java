package com.aireceptionist.knowledgebase.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record OcrProductEntry(
        @NotBlank String productName,
        @NotBlank String price
) {
}
