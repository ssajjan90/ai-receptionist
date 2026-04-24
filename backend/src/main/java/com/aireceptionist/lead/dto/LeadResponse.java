package com.aireceptionist.lead.dto;

import com.aireceptionist.lead.entity.Lead.LeadSource;
import com.aireceptionist.lead.entity.Lead.LeadStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LeadResponse {

    private Long id;
    private Long tenantId;
    private String customerName;
    private String phone;
    private String email;
    private String requirement;
    private LeadStatus status;
    private LeadSource source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
