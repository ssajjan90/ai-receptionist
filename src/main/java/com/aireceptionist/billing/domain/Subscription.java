package com.aireceptionist.billing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 7.1 (Task 2): a tenant's subscription row ({@code subscriptions}). Plain class, not a JPA
 * entity — see {@code SubscriptionRepository} for why billing reads/writes it over tenant-scoped
 * JDBC. One row per tenant (created at registration as BASIC/ACTIVE).
 */
public class Subscription {

    private final UUID id;
    private final UUID tenantId;
    private SubscriptionTier tier;
    private SubscriptionStatus status;
    private int conversationUsedThisMonth;
    private Integer conversationLimit;
    private Instant nextBillingDate;
    private Instant gracePeriodEndsAt;
    private Instant suspendedAt;
    private String razorpaySubscriptionId;
    private String razorpayPlanId;
    private final Instant createdAt;
    private Instant updatedAt;

    public Subscription(UUID id, UUID tenantId, SubscriptionTier tier, SubscriptionStatus status,
                        int conversationUsedThisMonth, Integer conversationLimit, Instant nextBillingDate,
                        Instant gracePeriodEndsAt, Instant suspendedAt, String razorpaySubscriptionId,
                        String razorpayPlanId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.tier = tier;
        this.status = status;
        this.conversationUsedThisMonth = conversationUsedThisMonth;
        this.conversationLimit = conversationLimit;
        this.nextBillingDate = nextBillingDate;
        this.gracePeriodEndsAt = gracePeriodEndsAt;
        this.suspendedAt = suspendedAt;
        this.razorpaySubscriptionId = razorpaySubscriptionId;
        this.razorpayPlanId = razorpayPlanId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A missing row (tenants created before Epic 7 provisioning) behaves as a fresh BASIC plan. */
    public static Subscription basic(UUID tenantId, Instant now) {
        return new Subscription(UUID.randomUUID(), tenantId, SubscriptionTier.BASIC, SubscriptionStatus.ACTIVE,
                0, SubscriptionTier.BASIC.conversationLimit(), null, null, null, null, null, now, now);
    }

    public void changeTier(SubscriptionTier newTier, Instant now) {
        this.tier = newTier;
        this.conversationLimit = newTier.conversationLimit();
        this.updatedAt = now;
    }

    /** Story 7.2 (AC4) / 7.4 (AC3). */
    public void markPaid(Instant nextBillingDate, Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.gracePeriodEndsAt = null;
        this.suspendedAt = null;
        if (nextBillingDate != null) {
            this.nextBillingDate = nextBillingDate;
        }
        this.updatedAt = now;
    }

    /** Story 7.2 (AC5). Returns false if already in grace/suspended (don't restart the clock). */
    public boolean startGracePeriod(Instant graceEndsAt, Instant now) {
        if (status == SubscriptionStatus.PAST_DUE || status == SubscriptionStatus.SUSPENDED) {
            return false;
        }
        this.status = SubscriptionStatus.PAST_DUE;
        this.gracePeriodEndsAt = graceEndsAt;
        this.updatedAt = now;
        return true;
    }

    /** Story 7.4 (AC1). */
    public void suspend(Instant now) {
        this.status = SubscriptionStatus.SUSPENDED;
        this.suspendedAt = now;
        this.updatedAt = now;
    }

    public void attachRazorpay(String razorpaySubscriptionId, String planId, Instant now) {
        this.razorpaySubscriptionId = razorpaySubscriptionId;
        this.razorpayPlanId = planId;
        this.updatedAt = now;
    }

    public void syncUsage(int used, Instant now) {
        this.conversationUsedThisMonth = used;
        this.updatedAt = now;
    }

    /** Story 7.3 (AC4): new billing month. */
    public void resetUsage(Instant nextBillingDate, Instant now) {
        this.conversationUsedThisMonth = 0;
        this.nextBillingDate = nextBillingDate;
        this.updatedAt = now;
    }

    public boolean isUnlimited() {
        return conversationLimit == null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public SubscriptionTier getTier() { return tier; }
    public SubscriptionStatus getStatus() { return status; }
    public int getConversationUsedThisMonth() { return conversationUsedThisMonth; }
    public Integer getConversationLimit() { return conversationLimit; }
    public Instant getNextBillingDate() { return nextBillingDate; }
    public Instant getGracePeriodEndsAt() { return gracePeriodEndsAt; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public String getRazorpayPlanId() { return razorpayPlanId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
