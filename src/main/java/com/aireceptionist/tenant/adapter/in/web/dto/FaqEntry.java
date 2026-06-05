package com.aireceptionist.tenant.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record FaqEntry(
        @NotBlank String question,
        @NotBlank String answer
) {
}
