package com.aireceptionist.leads.dto;

import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.domain.LeadStatus;

import java.time.Instant;
import java.util.UUID;

public record LeadResponse(
        UUID id,
        UUID tenantId,
        String customerName,
        String phone,
        String productIntent,
        LeadChannel channel,
        LeadStatus status,
        Instant consentTimestamp,
        boolean erased
) {}
