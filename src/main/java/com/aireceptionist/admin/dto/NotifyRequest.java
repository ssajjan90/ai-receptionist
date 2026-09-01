package com.aireceptionist.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotifyRequest(
        @NotBlank @Size(max = 1000) String message
) {
}
