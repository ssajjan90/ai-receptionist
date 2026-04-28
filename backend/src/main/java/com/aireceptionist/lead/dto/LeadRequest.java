package com.aireceptionist.lead.dto;

import com.aireceptionist.lead.entity.Lead.LeadSource;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @NotBlank(message = "Customer name is required")
    @Size(max = 255, message = "Customer name must be at most 255 characters")
    private String customerName;

    @Pattern(regexp = "^\\+?[0-9\\s\\-().]{7,20}$", message = "Invalid phone number")
    private String phone;

    @Email(message = "Invalid email")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @Size(max = 2000, message = "Requirement must be at most 2000 characters")
    private String requirement;
    private LeadSource source;
}
