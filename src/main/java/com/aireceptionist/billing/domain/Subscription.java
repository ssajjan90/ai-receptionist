package com.aireceptionist.billing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 7.1 (Task 2): a tenant's subscription row ({@code subscriptions}). Plain class, not a JPA
 * entity — see {@code SubscriptionRepository}. One row per tenant (created at registration as
 * BASIC/ACTIVE/300).
 *
 * <p>Code review: production code treats this as a <em>read model</em>. State changes go through
 * the repository's targeted, conditional UPDATEs (never a whole-row save of a possibly stale
 * snapshot). The mutators below exist for building rows in tests and for the in-memory default.
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
    private String pendingRazorpaySubscriptionId;
    private String pendingRazorpayPlanId;
    private String pendingCheckoutUrl;
    private Instant lastEventAt;
    private Instant billingAnchorAt;
    private final Instant createdAt;
    private Instant updatedAt;

    @SuppressWarnings("java:S107")
    public Subscription(UUID id, UUID tenantId, SubscriptionTier tier, SubscriptionStatus status,
                        int conversationUsedThisMonth, Integer conversationLimit, Instant nextBillingDate,
                        Instant gracePeriodEndsAt, Instant suspendedAt, String razorpaySubscriptionId,
                        String razorpayPlanId, String pendingRazorpaySubscriptionId, String pendingRazorpayPlanId,
                        String pendingCheckoutUrl, Instant lastEventAt, Instant billingAnchorAt,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.tier = tier;
        this.status = status;
        this.conversationUsedThisMonth = conversationUsedThisMonth;
        // Code review: a BASIC row must never read as unlimited, whatever wrote it.
        this.conversationLimit = conversationLimit == null ? tier.conversationLimit() : conversationLimit;
        this.nextBillingDate = nextBillingDate;
        this.gracePeriodEndsAt = gracePeriodEndsAt;
        this.suspendedAt = suspendedAt;
        this.razorpaySubscriptionId = razorpaySubscriptionId;
        this.razorpayPlanId = razorpayPlanId;
        this.pendingRazorpaySubscriptionId = pendingRazorpaySubscriptionId;
        this.pendingRazorpayPlanId = pendingRazorpayPlanId;
        this.pendingCheckoutUrl = pendingCheckoutUrl;
        this.lastEventAt = lastEventAt;
        this.billingAnchorAt = billingAnchorAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A missing row (tenants created before Epic 7 provisioning) behaves as a fresh BASIC plan. */
    public static Subscription basic(UUID tenantId, Instant now) {
        return new Subscription(UUID.randomUUID(), tenantId, SubscriptionTier.BASIC, SubscriptionStatus.ACTIVE,
                0, SubscriptionTier.BASIC.conversationLimit(), null, null, null, null, null, null, null, null,
                null, null, now, now);
    }

    // ---- test/seed mutators -------------------------------------------------------------

    public void changeTier(SubscriptionTier newTier, Instant now) {
        this.tier = newTier;
        this.conversationLimit = newTier.conversationLimit();
        this.updatedAt = now;
    }

    public void markPaid(Instant nextBillingDate, Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.gracePeriodEndsAt = null;
        this.suspendedAt = null;
        if (nextBillingDate != null) {
            this.nextBillingDate = nextBillingDate;
        }
        this.updatedAt = now;
    }

    public boolean startGracePeriod(Instant graceEndsAt, Instant now) {
        if (status == SubscriptionStatus.PAST_DUE || status == SubscriptionStatus.SUSPENDED) {
            return false;
        }
        this.status = SubscriptionStatus.PAST_DUE;
        this.gracePeriodEndsAt = graceEndsAt;
        this.updatedAt = now;
        return true;
    }

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

    public void resetUsage(Instant nextBillingDate, Instant now) {
        this.conversationUsedThisMonth = 0;
        this.nextBillingDate = nextBillingDate;
        this.updatedAt = now;
    }

    public boolean isUnlimited() {
        return conversationLimit == null;
    }

    /** A checkout has been paid and its Razorpay subscription is live. */
    public boolean hasActivePaidSubscription() {
        return razorpaySubscriptionId != null
                && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE);
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
    public String getPendingRazorpaySubscriptionId() { return pendingRazorpaySubscriptionId; }
    public String getPendingRazorpayPlanId() { return pendingRazorpayPlanId; }
    public String getPendingCheckoutUrl() { return pendingCheckoutUrl; }
    public Instant getLastEventAt() { return lastEventAt; }
    public Instant getBillingAnchorAt() { return billingAnchorAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
