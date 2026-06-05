package com.aireceptionist.tenant.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank String businessName,
        @NotBlank String ownerName,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{6,14}$") String ownerPhone,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{6,14}$") String businessPhone,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8) String password
) {
}
