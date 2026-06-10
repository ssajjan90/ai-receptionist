package com.aireceptionist.leads.dto;

import com.aireceptionist.leads.domain.LeadChannel;

import java.time.Instant;
import java.util.UUID;

public record CreateLeadCommand(
        UUID tenantId,
        String customerName,
        String phone,
        String productIntent,
        LeadChannel channel,
        String consentChannel,
        Instant consentedAt
) {}
