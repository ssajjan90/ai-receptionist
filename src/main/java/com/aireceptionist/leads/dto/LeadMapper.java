package com.aireceptionist.leads.dto;

import com.aireceptionist.leads.domain.Lead;
import org.springframework.stereotype.Component;

@Component
public class LeadMapper {

    public LeadResponse toResponse(Lead lead) {
        if (Boolean.TRUE.equals(lead.getErased())) {
            return new LeadResponse(
                    lead.getId(),
                    lead.getTenantId(),
                    null,
                    null,
                    lead.getProductIntent(),
                    lead.getChannel(),
                    lead.getStatus(),
                    null,
                    lead.getCreatedAt(),
                    true
            );
        }
        return new LeadResponse(
                lead.getId(),
                lead.getTenantId(),
                lead.getCustomerName(),
                lead.getPhone(),
                lead.getProductIntent(),
                lead.getChannel(),
                lead.getStatus(),
                lead.getConsentTimestamp(),
                lead.getCreatedAt(),
                false
        );
    }
}
