package com.aireceptionist.common.event;

import java.time.Instant;
import java.util.UUID;

// Relocated from knowledgebase.event (code review of story 5-1's AdminModuleTest, 2026-09-01):
// common.ai.AiConfidenceGateAspect (a cross-cutting aspect in the foundational common module)
// constructs this event directly, which created a module cycle (common -> knowledgebase for this
// construction, knowledgebase -> common for everything else). A foundational module reaching into
// a specific business module's event type is backwards; this event's actual home is alongside its
// AiReceptionistEvent base class. See deferred W82.
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
