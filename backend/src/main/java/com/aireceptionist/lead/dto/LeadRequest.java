package com.aireceptionist.lead.dto;

import com.aireceptionist.lead.entity.Lead.LeadSource;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LeadRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    private String customerName;
    private String phone;
    private String email;
    private String requirement;
    private LeadSource source;
}
