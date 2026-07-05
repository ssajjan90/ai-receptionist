package com.aireceptionist.leads.dto;

import com.aireceptionist.leads.domain.LeadStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateLeadStatusRequest(
        @NotNull LeadStatus status
) {}
