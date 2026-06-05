package com.aireceptionist.lead.dto;

import com.aireceptionist.lead.entity.Lead.LeadStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LeadStatusRequest {

    @NotNull(message = "Status is required")
    private LeadStatus status;
}
