package com.aireceptionist.tenant.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{6,14}$") String ownerPhone,
        @NotBlank String otp
) {
}
