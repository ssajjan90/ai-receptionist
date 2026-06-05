package com.aireceptionist.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantRequest {

    @NotBlank(message = "Tenant name is required")
    private String name;

    private String industry;

    private String phone;

    @Email(message = "Invalid email address")
    private String email;

    private String address;

    private String workingHours;

    private String defaultLanguage;

    private String supportedLanguages;
}
