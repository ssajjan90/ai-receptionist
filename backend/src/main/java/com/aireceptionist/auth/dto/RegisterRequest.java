package com.aireceptionist.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @Email(message = "Invalid email")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    // tenantId is required for standard registration flows.
    // SUPER_ADMIN users are provisioned via a separate admin-only flow and may omit tenantId.
    @NotNull(message = "Tenant ID is required")
    @Min(value = 1, message = "Tenant ID must be greater than 0")
    private Long tenantId;
}
