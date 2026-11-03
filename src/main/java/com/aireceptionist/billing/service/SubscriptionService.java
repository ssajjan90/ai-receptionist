package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.billing.dto.SubscriptionStatusResponse;
import com.aireceptionist.billing.event.SubscriptionTierChangedEvent;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.event.OwnerNotificationRequestedEvent;
import com.aireceptionist.tenant.port.in.TenantBillingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Epic 7 subscription state machine.
 * <ul>
 *   <li>7.1: tier changes keep {@code subscriptions.tier} and {@code tenants.tier} (what the JWT
 *       and voice gating read) in step, and publish {@link SubscriptionTierChangedEvent}.</li>
 *   <li>7.2: payment success → ACTIVE (+ tier from plan); failure → PAST_DUE with a 3-day grace.</li>
 *   <li>7.4: grace expiry → SUSPENDED + tenant PAYMENT_SUSPENDED; payment → restored. Every
 *       transition is audited (AC5) and the owner is told on WhatsApp via
 *       {@link OwnerNotificationRequestedEvent}.</li>
 * </ul>
 * Events are published inside a short transaction: {@code @ApplicationModuleListener}s only fire
 * after a commit, and webhook/scheduler callers have none of their own.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    static final Duration GRACE_PERIOD = Duration.ofDays(3);

    static final String GRACE_START_MESSAGE = "⚠️ Payment issue detected. Please renew your CallSahayak subscription "
            + "to avoid service interruption. Grace period ends in 3 days.";
    static final String GRACE_24H_MESSAGE =
            "⚠️ Your CallSahayak subscription will be suspended in 24 hours due to payment failure.";
    static final String SUSPENSION_MESSAGE =
            "❌ Your CallSahayak AI service has been suspended. Please make payment to restore.";
    static final String RESTORATION_MESSAGE = "✅ Your CallSahayak service has been restored. Welcome back!";

    public static final String AUDIT_PAYMENT_GRACE_STARTED = "PAYMENT_GRACE_STARTED";
    public static final String AUDIT_PAYMENT_SUSPENSION = "PAYMENT_SUSPENSION";
    public static final String AUDIT_PAYMENT_RESTORED = "PAYMENT_RESTORED";
    public static final String AUDIT_TIER_CHANGED = "SUBSCRIPTION_TIER_CHANGED";

    private final SubscriptionRepository repository;
    private final TenantBillingUseCase tenantBilling;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final AuditLogRepository auditLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final BillingPlans plans;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository repository,
                               TenantBillingUseCase tenantBilling,
                               ApplicationEventPublisher eventPublisher,
                               PlatformTransactionManager transactionManager,
                               AuditLogRepository auditLogRepository,
                               StringRedisTemplate redisTemplate,
                               BillingPlans plans,
                               Clock clock) {
        this.repository = repository;
        this.tenantBilling = tenantBilling;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditLogRepository = auditLogRepository;
        this.redisTemplate = redisTemplate;
        this.plans = plans;
        this.clock = clock;
    }

    public Subscription load(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElseGet(() -> Subscription.basic(tenantId, Instant.now(clock)));
    }

    /** Story 7.1 (AC4). */
    public void updateTierFromWebhook(UUID tenantId, SubscriptionTier newTier) {
        Subscription subscription = load(tenantId);
        SubscriptionTier oldTier = subscription.getTier();
        subscription.changeTier(newTier, Instant.now(clock));
        repository.save(subscription);
        if (oldTier != newTier) {
            tenantBilling.changeTier(tenantId, newTier.name());
            audit(tenantId, AUDIT_TIER_CHANGED, oldTier + "->" + newTier);
            if (newTier.conversationLimit() == null || newTier.rank() > oldTier.rank()) {
                clearLimitFlag(tenantId); // upgraded past the BASIC cap
            }
            log.warn("Tenant {} tier changed {} -> {}; existing JWTs keep the old tier claim until they expire",
                    tenantId, oldTier, newTier);
        }
        publish(new SubscriptionTierChangedEvent(tenantId.toString(), oldTier.name(), newTier.name(),
                subscription.getStatus().name(), Instant.now(clock)));
    }

    /** Story 7.1 (AC5) / 7.3 (AC5): usage is read live from Redis, falling back to the synced DB value. */
    public SubscriptionStatusResponse getSubscriptionStatus(UUID tenantId) {
        Subscription s = load(tenantId);
        int used = s.getConversationUsedThisMonth();
        try {
            String live = redisTemplate.opsForValue().get(BillingRedisKeys.conversationCount(tenantId.toString()));
            if (live != null) {
                used = Integer.parseInt(live);
            }
        } catch (Exception ex) {
            log.debug("Live usage unavailable for tenant={}, using DB value: {}", tenantId, ex.getMessage());
        }
        return new SubscriptionStatusResponse(s.getTier().name(), s.getStatus().name(), used,
                s.getConversationLimit(), s.getNextBillingDate());
    }

    /** Story 7.2 (Task 4). */
    public void attachCheckout(UUID tenantId, String razorpaySubscriptionId, String planId) {
        Subscription s = load(tenantId);
        s.attachRazorpay(razorpaySubscriptionId, planId, Instant.now(clock));
        repository.save(s);
    }

    /**
     * Story 7.2 (AC4): payment captured. Tier follows the plan; a renewal (billing date reached)
     * also starts a fresh usage month (7.3 AC4) since the new {@code current_end} moves the date on.
     */
    public void handlePaymentSuccess(UUID tenantId, String planId, Instant nextBillingDate) {
        Instant now = Instant.now(clock);
        Subscription s = load(tenantId);
        boolean renewal = s.getNextBillingDate() == null || !s.getNextBillingDate().isAfter(now.plus(Duration.ofHours(1)));
        s.markPaid(nextBillingDate, now);
        if (renewal) {
            s.resetUsage(nextBillingDate != null ? nextBillingDate : now.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant(), now);
            resetUsageCounters(tenantId.toString());
        }
        repository.save(s);
        plans.tierFor(planId).ifPresentOrElse(
                tier -> updateTierFromWebhook(tenantId, tier),
                () -> log.warn("Unknown Razorpay plan id {} for tenant={} — tier unchanged", planId, tenantId));
        log.info("Payment success for tenant={} plan={}", tenantId, planId);
    }

    /** Story 7.2 (AC5): start the 3-day grace period (once) and tell the owner. */
    public void handlePaymentFailure(UUID tenantId) {
        Instant now = Instant.now(clock);
        Subscription s = load(tenantId);
        if (!s.startGracePeriod(now.plus(GRACE_PERIOD), now)) {
            log.info("Payment failure for tenant={} while already {} — grace clock not restarted", tenantId, s.getStatus());
            return;
        }
        repository.save(s);
        audit(tenantId, AUDIT_PAYMENT_GRACE_STARTED, "grace ends " + s.getGracePeriodEndsAt());
        notifyOwnerOnce(tenantId, BillingRedisKeys.graceStartNotified(tenantId.toString()), GRACE_START_MESSAGE);
        publish(new SubscriptionTierChangedEvent(tenantId.toString(), s.getTier().name(), s.getTier().name(),
                SubscriptionStatus.PAST_DUE.name(), now));
    }

    /** Story 7.4 (AC1). */
    public void suspendTenant(UUID tenantId) {
        Instant now = Instant.now(clock);
        Subscription s = load(tenantId);
        if (s.getStatus() == SubscriptionStatus.SUSPENDED) {
            return;
        }
        s.suspend(now);
        repository.save(s);
        tenantBilling.suspendForPayment(tenantId);
        audit(tenantId, AUDIT_PAYMENT_SUSPENSION, "grace period expired");
        notifyOwner(tenantId, SUSPENSION_MESSAGE);
        log.warn("Tenant {} suspended for non-payment", tenantId);
    }

    /**
     * Story 7.4 (AC3): payment arrived for a tenant in grace or suspension — restore immediately.
     * Returns false (no-op) for a tenant in good standing (a normal renewal).
     */
    public boolean restoreTenant(UUID tenantId) {
        Instant now = Instant.now(clock);
        Subscription s = load(tenantId);
        if (s.getStatus() != SubscriptionStatus.SUSPENDED && s.getStatus() != SubscriptionStatus.PAST_DUE) {
            return false;
        }
        s.markPaid(null, now);
        repository.save(s);
        tenantBilling.restoreFromPaymentSuspension(tenantId);
        clearLimitFlag(tenantId);
        clearGraceNotifications(tenantId.toString());
        audit(tenantId, AUDIT_PAYMENT_RESTORED, "payment received");
        notifyOwner(tenantId, RESTORATION_MESSAGE);
        log.info("Tenant {} restored after payment", tenantId);
        return true;
    }

    public void notifyOwner(UUID tenantId, String message) {
        publish(new OwnerNotificationRequestedEvent(tenantId.toString(), message));
    }

    /** Sends once per key (Redis SETNX); falls back to sending if Redis is unavailable. */
    public void notifyOwnerOnce(UUID tenantId, String dedupeKey, String message) {
        boolean first = true;
        try {
            first = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(dedupeKey, "sent", Duration.ofDays(35)));
        } catch (Exception ex) {
            log.warn("Notification dedupe unavailable ({}), sending anyway: {}", dedupeKey, ex.getMessage());
        }
        if (first) {
            notifyOwner(tenantId, message);
        }
    }

    public void resetUsageCounters(String tenantId) {
        try {
            redisTemplate.delete(java.util.List.of(
                    BillingRedisKeys.conversationCount(tenantId), BillingRedisKeys.limitReached(tenantId),
                    BillingRedisKeys.alert80(tenantId), BillingRedisKeys.alert100(tenantId)));
        } catch (Exception ex) {
            log.warn("Could not reset usage counters for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    private void clearLimitFlag(UUID tenantId) {
        try {
            redisTemplate.delete(BillingRedisKeys.limitReached(tenantId.toString()));
        } catch (Exception ex) {
            log.warn("Could not clear limit flag for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    private void clearGraceNotifications(String tenantId) {
        try {
            redisTemplate.delete(java.util.List.of(
                    BillingRedisKeys.graceStartNotified(tenantId), BillingRedisKeys.grace24hNotified(tenantId)));
        } catch (Exception ex) {
            log.debug("Could not clear grace notification keys for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    public void audit(UUID tenantId, String eventType, String detail) {
        try {
            auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), tenantId, eventType, null,
                    detail.length() > 64 ? detail.substring(0, 64) : detail, Instant.now(clock)));
        } catch (Exception ex) {
            log.error("Failed to write {} audit entry for tenant={}: {}", eventType, tenantId, ex.getMessage());
        }
    }

    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }
}
