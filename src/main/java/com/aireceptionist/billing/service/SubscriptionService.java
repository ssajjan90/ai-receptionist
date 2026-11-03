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
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
import com.aireceptionist.tenant.port.in.TenantBillingUseCase;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Epic 7 subscription state machine.
 * <ul>
 *   <li>7.1: tier changes keep {@code subscriptions.tier} and {@code tenants.tier} (JWT/voice
 *       source) in step and publish {@link SubscriptionTierChangedEvent}.</li>
 *   <li>7.2: payment success → ACTIVE (+ tier from plan); {@code halted} → PAST_DUE with a 3-day
 *       grace; {@code cancelled} → downgrade to BASIC, data kept (code-review decision).</li>
 *   <li>7.4: grace expiry → SUSPENDED + tenant PAYMENT_SUSPENDED; payment → restored.</li>
 * </ul>
 * Code review: each transition is one transaction spanning the subscription row and the tenant row
 * (the JDBC repository shares the JPA transaction's connection), and every write is a targeted
 * compare-and-set — so concurrent jobs/webhooks can't overwrite each other and a failure can't
 * leave the two rows disagreeing. Tenant-side calls are idempotent, so re-delivery heals anything
 * left over. Owner notifications go out only after the transition commits, in their own
 * transaction, so a rollback elsewhere can't swallow them.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    static final Duration GRACE_PERIOD = Duration.ofDays(3);
    private static final Duration CHECKOUT_LOCK_TTL = Duration.ofSeconds(30);

    static final String GRACE_START_MESSAGE = "⚠️ Payment issue detected. Please renew your CallSahayak subscription "
            + "to avoid service interruption. Grace period ends in 3 days.";
    static final String GRACE_24H_MESSAGE =
            "⚠️ Your CallSahayak subscription will be suspended in 24 hours due to payment failure.";
    static final String SUSPENSION_MESSAGE =
            "❌ Your CallSahayak AI service has been suspended. Please make payment to restore.";
    static final String RESTORATION_MESSAGE = "✅ Your CallSahayak service has been restored. Welcome back!";
    static final String PAYMENT_RECEIVED_STILL_BLOCKED_MESSAGE = "✅ Payment received, thank you. Your account is "
            + "still on hold for another reason — please contact CallSahayak support to restore service.";
    static final String CANCELLED_MESSAGE = "ℹ️ Your CallSahayak plan was cancelled. You're now on the Basic plan "
            + "(WhatsApp AI, 300 conversations/month). Your data and settings are kept.";

    public static final String AUDIT_PAYMENT_GRACE_STARTED = "PAYMENT_GRACE_STARTED";
    public static final String AUDIT_PAYMENT_SUSPENSION = "PAYMENT_SUSPENSION";
    public static final String AUDIT_PAYMENT_RESTORED = "PAYMENT_RESTORED";
    public static final String AUDIT_PAYMENT_RECEIVED = "PAYMENT_RECEIVED";
    public static final String AUDIT_TIER_CHANGED = "SUBSCRIPTION_TIER_CHANGED";
    public static final String AUDIT_SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED";

    private final SubscriptionRepository repository;
    private final TenantBillingUseCase tenantBilling;
    private final GetTenantStatusUseCase tenantStatus;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final TransactionTemplate independentTransaction;
    private final AuditLogRepository auditLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final BillingPlans plans;
    private final RazorpayGateway razorpayGateway;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository repository,
                               TenantBillingUseCase tenantBilling,
                               GetTenantStatusUseCase tenantStatus,
                               ApplicationEventPublisher eventPublisher,
                               PlatformTransactionManager transactionManager,
                               AuditLogRepository auditLogRepository,
                               StringRedisTemplate redisTemplate,
                               BillingPlans plans,
                               RazorpayGateway razorpayGateway,
                               Clock clock) {
        this.repository = repository;
        this.tenantBilling = tenantBilling;
        this.tenantStatus = tenantStatus;
        this.eventPublisher = eventPublisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditLogRepository = auditLogRepository;
        this.redisTemplate = redisTemplate;
        this.plans = plans;
        this.razorpayGateway = razorpayGateway;
        this.clock = clock;
    }

    public Subscription load(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElseGet(() -> Subscription.basic(tenantId, Instant.now(clock)));
    }

    private Subscription ensure(UUID tenantId) {
        repository.ensureExists(tenantId, Instant.now(clock));
        return repository.findByTenantId(tenantId).orElseThrow();
    }

    /** Story 7.1 (AC4). Idempotent: always re-asserts tenants.tier, so a re-delivered event heals drift. */
    public void updateTierFromWebhook(UUID tenantId, SubscriptionTier newTier) {
        Instant now = Instant.now(clock);
        SubscriptionTier oldTier = transaction.execute(tx -> {
            Subscription s = ensure(tenantId);
            repository.updateTier(tenantId, newTier, now);
            tenantBilling.changeTier(tenantId, newTier.name());
            if (s.getTier() != newTier) {
                // Code review: moving onto a limited plan mid-cycle starts a fresh usage month —
                // otherwise e.g. 1,000 PRO conversations would block a tenant who just paid for BASIC.
                if (newTier.conversationLimit() != null) {
                    startNewUsageMonth(tenantId, s, now);
                }
                publish(new SubscriptionTierChangedEvent(tenantId.toString(), s.getTier().name(), newTier.name(),
                        s.getStatus().name(), now));
            }
            return s.getTier();
        });
        if (oldTier != newTier) {
            if (newTier.conversationLimit() != null) {
                resetUsageCounters(tenantId.toString());
            } else {
                clearLimitFlag(tenantId);
            }
            audit(tenantId, AUDIT_TIER_CHANGED, oldTier + "->" + newTier);
            log.warn("Tenant {} tier changed {} -> {}; existing JWTs keep the old tier claim until they expire",
                    tenantId, oldTier, newTier);
        }
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

    /**
     * Story 7.2 (AC1) with the code-review decision: a tenant with a paid, live subscription can't
     * start another (409 — plan changes are a later story); an unpaid pending checkout for the same
     * plan is reused, and a per-tenant lock stops double-clicks creating two Razorpay subscriptions.
     */
    public String checkout(UUID tenantId, String planId) {
        Subscription s = ensure(tenantId);
        if (s.hasActivePaidSubscription()) {
            throw new BusinessRuleException("SUBSCRIPTION_ALREADY_ACTIVE",
                    "You already have an active paid subscription. Plan changes aren't supported yet.");
        }
        if (planId.equals(s.getPendingRazorpayPlanId()) && s.getPendingCheckoutUrl() != null) {
            return s.getPendingCheckoutUrl();
        }
        String lockKey = "billing:checkout:lock:" + tenantId;
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, "1", CHECKOUT_LOCK_TTL))) {
            throw new BusinessRuleException("CHECKOUT_IN_PROGRESS", "A checkout is already being created. Please wait a moment.");
        }
        try {
            RazorpayGateway.CreatedSubscription created;
            try {
                created = razorpayGateway.createSubscription(planId, tenantId);
            } catch (CallNotPermittedException ex) {
                throw new ExternalServiceException("Payment provider temporarily unavailable", ex);
            }
            repository.setPendingCheckout(tenantId, created.id(), planId, created.checkoutUrl(), Instant.now(clock));
            return created.checkoutUrl();
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * Story 7.2 (AC4): payment captured. A pending checkout's first charge makes it the live
     * subscription. A renewal (billing date reached) also starts a new usage month (7.3 AC4).
     * Always publishes {@link SubscriptionTierChangedEvent} (also for an unrecognised plan id).
     */
    public void handlePaymentSuccess(UUID tenantId, String razorpaySubscriptionId, String planId, Instant nextBillingDate) {
        Instant now = Instant.now(clock);
        boolean renewed = Boolean.TRUE.equals(transaction.execute(tx -> {
            Subscription s = ensure(tenantId);
            if (razorpaySubscriptionId != null) {
                repository.promotePending(tenantId, razorpaySubscriptionId, now);
            }
            boolean renewal = s.getNextBillingDate() == null || !s.getNextBillingDate().isAfter(now.plus(Duration.ofHours(1)));
            if (renewal) {
                // Before markPaid moves next_billing_date: the reset is compare-and-set on the old date.
                startNewUsageMonth(tenantId, s, now, nextBillingDate);
            }
            repository.markPaid(tenantId, nextBillingDate, now);
            return renewal;
        }));
        if (renewed) {
            resetUsageCounters(tenantId.toString());
        }
        plans.tierFor(planId).ifPresentOrElse(
                tier -> updateTierFromWebhook(tenantId, tier),
                () -> {
                    log.warn("Unknown Razorpay plan id {} for tenant={} — tier unchanged", planId, tenantId);
                    Subscription s = load(tenantId);
                    publishNow(new SubscriptionTierChangedEvent(tenantId.toString(), s.getTier().name(),
                            s.getTier().name(), s.getStatus().name(), now));
                });
        log.info("Payment success for tenant={} plan={}", tenantId, planId);
    }

    /** Story 7.2 (AC5): {@code subscription.halted} — start the 3-day grace period (once) and tell the owner. */
    public void handlePaymentFailure(UUID tenantId) {
        Instant now = Instant.now(clock);
        Subscription s = ensure(tenantId);
        boolean started = Boolean.TRUE.equals(transaction.execute(tx -> {
            boolean applied = repository.startGrace(tenantId, now.plus(GRACE_PERIOD), now);
            if (applied) {
                publish(new SubscriptionTierChangedEvent(tenantId.toString(), s.getTier().name(), s.getTier().name(),
                        SubscriptionStatus.PAST_DUE.name(), now));
            }
            return applied;
        }));
        if (!started) {
            log.info("Payment failure for tenant={} while already {} — grace clock not restarted", tenantId, s.getStatus());
            return;
        }
        audit(tenantId, AUDIT_PAYMENT_GRACE_STARTED, "grace ends " + now.plus(GRACE_PERIOD));
        notifyOwnerOnce(tenantId, BillingRedisKeys.graceStartNotified(tenantId.toString()), GRACE_START_MESSAGE);
    }

    /**
     * Code-review decision: {@code subscription.cancelled} is a voluntary cancellation, not a
     * payment failure — drop to BASIC, keep data, no grace/suspension. A tenant already suspended
     * for non-payment stays suspended (the debt is unchanged) but can check out again.
     */
    public void handleCancellation(UUID tenantId) {
        Instant now = Instant.now(clock);
        Subscription before = ensure(tenantId);
        transaction.executeWithoutResult(tx -> {
            repository.cancelToBasic(tenantId, now);
            tenantBilling.changeTier(tenantId, SubscriptionTier.BASIC.name());
            if (before.getTier() != SubscriptionTier.BASIC) {
                startNewUsageMonth(tenantId, before, now);
            }
            publish(new SubscriptionTierChangedEvent(tenantId.toString(), before.getTier().name(),
                    SubscriptionTier.BASIC.name(), SubscriptionStatus.ACTIVE.name(), now));
        });
        if (before.getTier() != SubscriptionTier.BASIC) {
            resetUsageCounters(tenantId.toString());
        }
        clearGraceNotifications(tenantId.toString());
        audit(tenantId, AUDIT_SUBSCRIPTION_CANCELLED, before.getTier() + "->BASIC");
        notifyOwner(tenantId, CANCELLED_MESSAGE);
    }

    /** Story 7.4 (AC1). Compare-and-set: a payment that lands first wins. */
    public boolean suspendTenant(UUID tenantId) {
        Instant now = Instant.now(clock);
        boolean suspended = Boolean.TRUE.equals(transaction.execute(tx -> {
            boolean applied = repository.suspendIfGraceExpired(tenantId, now);
            if (applied) {
                tenantBilling.suspendForPayment(tenantId);
            }
            return applied;
        }));
        if (suspended) {
            audit(tenantId, AUDIT_PAYMENT_SUSPENSION, "grace period expired");
            notifyOwner(tenantId, SUSPENSION_MESSAGE);
            log.warn("Tenant {} suspended for non-payment", tenantId);
        }
        return suspended;
    }

    /**
     * Story 7.4 (AC3): payment arrived — restore immediately. The tenant-side restore always runs
     * (idempotent), so a tenant left PAYMENT_SUSPENDED by an earlier partial failure is healed too.
     * If the tenant stays blocked for another reason (admin suspension), the owner is told the
     * payment was received rather than that service is back.
     */
    public boolean restoreTenant(UUID tenantId) {
        Instant now = Instant.now(clock);
        boolean[] changed = new boolean[2];
        transaction.executeWithoutResult(tx -> {
            changed[0] = repository.restore(tenantId, now);
            changed[1] = tenantBilling.restoreFromPaymentSuspension(tenantId);
        });
        if (!changed[0] && !changed[1]) {
            return false;
        }
        clearLimitFlag(tenantId);
        clearGraceNotifications(tenantId.toString());
        boolean live = tenantStatus.getStatus(tenantId).map("LIVE"::equals).orElse(false);
        if (live) {
            audit(tenantId, AUDIT_PAYMENT_RESTORED, "payment received");
            notifyOwner(tenantId, RESTORATION_MESSAGE);
            log.info("Tenant {} restored after payment", tenantId);
        } else {
            audit(tenantId, AUDIT_PAYMENT_RECEIVED, "payment received; tenant still blocked");
            notifyOwner(tenantId, PAYMENT_RECEIVED_STILL_BLOCKED_MESSAGE);
            log.info("Payment received for tenant {} but it remains blocked (not payment-suspended)", tenantId);
        }
        return true;
    }

    /** Owner WhatsApp notification, committed independently of any caller transaction. */
    public void notifyOwner(UUID tenantId, String message) {
        publishNow(new OwnerNotificationRequestedEvent(tenantId.toString(), message));
    }

    /**
     * Once per key (Redis SETNX). Code review: if Redis is unavailable the reminder is skipped —
     * the hourly grace job retries, whereas "send anyway" messaged every owner every hour.
     */
    public void notifyOwnerOnce(UUID tenantId, String dedupeKey, String message) {
        boolean first;
        try {
            first = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(dedupeKey, "sent", Duration.ofDays(35)));
        } catch (Exception ex) {
            log.warn("Notification dedupe unavailable ({}), will retry later: {}", dedupeKey, ex.getMessage());
            return;
        }
        if (first) {
            notifyOwner(tenantId, message);
        }
    }

    /**
     * Story 7.3 (AC4): begin a new usage month in the DB, anchored to a fixed day of month.
     * Redis counters are cleared by {@link #resetUsageCounters} after the commit.
     */
    void startNewUsageMonth(UUID tenantId, Subscription s, Instant now) {
        startNewUsageMonth(tenantId, s, now, null);
    }

    private void startNewUsageMonth(UUID tenantId, Subscription s, Instant now, Instant razorpayNextBilling) {
        Instant anchor = s.getBillingAnchorAt() != null ? s.getBillingAnchorAt() : now;
        Instant next = razorpayNextBilling != null ? razorpayNextBilling : nextRenewal(anchor, now);
        repository.resetUsage(tenantId, s.getNextBillingDate(), next, anchor, now);
    }

    /** First anchor + N months strictly after {@code now} (from the anchor each time — no 31st → 28th drift). */
    public static Instant nextRenewal(Instant anchor, Instant now) {
        for (int months = 1; months <= 1200; months++) {
            Instant candidate = anchor.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return now.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
    }

    /** Clears this month's Redis usage state. Throws if Redis is unavailable (callers decide). */
    public void resetUsageCounters(String tenantId) {
        redisTemplate.delete(List.of(
                BillingRedisKeys.conversationCount(tenantId), BillingRedisKeys.limitReached(tenantId),
                BillingRedisKeys.alert80(tenantId), BillingRedisKeys.alert100(tenantId)));
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
            redisTemplate.delete(List.of(
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

    /** Inside the current transition transaction: delivered only if it commits. */
    private void publish(Object event) {
        eventPublisher.publishEvent(event);
    }

    /** Own transaction: delivered even if an enclosing transaction (e.g. the WhatsApp listener's) rolls back. */
    private void publishNow(Object event) {
        independentTransaction.executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }
}
