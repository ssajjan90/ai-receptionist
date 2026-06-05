package com.aireceptionist.tenant.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductEntry(
        @NotBlank String productName,
        @NotBlank String price
) {
}
