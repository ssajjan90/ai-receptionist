package com.aireceptionist.billing.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;

/**
 * Story 7.1 (AC4) / 7.2: the tenant's tier or subscription status changed. JWT invalidation is
 * MVP-deferred (Task 3): tokens carry the old {@code tier} until they expire (60 min). Voice and
 * WhatsApp gating read {@code tenants.tier}/status from the DB, so they switch immediately.
 */
public class SubscriptionTierChangedEvent extends AiReceptionistEvent {

    private final String oldTier;
    private final String newTier;
    private final String status;
    private final Instant changedAt;

    public SubscriptionTierChangedEvent(String tenantId, String oldTier, String newTier, String status, Instant changedAt) {
        super(tenantId);
        this.oldTier = oldTier;
        this.newTier = newTier;
        this.status = status;
        this.changedAt = changedAt;
    }

    public String getOldTier() { return oldTier; }
    public String getNewTier() { return newTier; }
    public String getStatus() { return status; }
    public Instant getChangedAt() { return changedAt; }
}
