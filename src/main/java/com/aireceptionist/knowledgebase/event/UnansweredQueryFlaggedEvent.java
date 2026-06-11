package com.aireceptionist.knowledgebase.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;
import java.util.UUID;

public class UnansweredQueryFlaggedEvent extends AiReceptionistEvent {

    private final String customerPhone;
    private final String originalQuery;
    private final String ownerPhone;
    private final Instant occurredAt;
    private final UUID auditLogId;

    public UnansweredQueryFlaggedEvent(String tenantId, String customerPhone,
                                       String originalQuery, String ownerPhone, UUID auditLogId) {
        super(tenantId);
        this.customerPhone = customerPhone;
        this.originalQuery = originalQuery;
        this.ownerPhone = ownerPhone;
        this.occurredAt = Instant.now();
        this.auditLogId = auditLogId;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getAuditLogId() {
        return auditLogId;
    }
}
