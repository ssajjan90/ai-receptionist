package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.common.billing.ConversationUsagePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Story 7.3 (AC1-AC3): atomic Redis metering ({@code INCR tenant:{id}:conv:month}) with 80% and
 * 100% owner alerts for limited (BASIC) plans. Thresholds use {@code >=} with a once-per-month
 * Redis marker rather than the story's {@code ==}: an exact-equality check silently misses the
 * alert if the counter ever skips a value (e.g. reseeded from the DB after a Redis restart).
 * Markers live until the monthly reset (35-day safety TTL) instead of the Dev Notes' 24h — a 24h
 * marker would re-send the 80% alert every day for the rest of the month.
 */
@Service
public class UsageAlertService implements ConversationUsagePort {

    private static final Logger log = LoggerFactory.getLogger(UsageAlertService.class);
    private static final Duration MARKER_TTL = Duration.ofDays(35);

    static final String ALERT_80_MESSAGE = "📊 Heads up: you've used %d of your %d monthly AI conversations (80%%). "
            + "Upgrade to Pro for unlimited conversations so your customers keep getting instant replies.";
    static final String ALERT_100_MESSAGE = "🚫 You've used all %d AI conversations included this month. New customer "
            + "messages now get a 'service limit reached' reply. Upgrade to Pro to resume AI replies immediately.";

    private final SubscriptionService subscriptionService;
    private final StringRedisTemplate redisTemplate;

    public UsageAlertService(SubscriptionService subscriptionService, StringRedisTemplate redisTemplate) {
        this.subscriptionService = subscriptionService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isLimitReached(String tenantId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BillingRedisKeys.limitReached(tenantId)));
        } catch (Exception ex) {
            // Fail open: a Redis outage must not silence every customer.
            log.warn("Usage limit check unavailable for tenant={}: {}", tenantId, ex.getMessage());
            return false;
        }
    }

    @Override
    public void recordConversation(String tenantId) {
        try {
            Subscription subscription = subscriptionService.load(UUID.fromString(tenantId));
            String key = BillingRedisKeys.conversationCount(tenantId);
            // Seed from the last synced DB value if Redis lost the key, so a restart doesn't reset usage.
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(subscription.getConversationUsedThisMonth()),
                    MARKER_TTL);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null) {
                checkAndAlert(tenantId, count, subscription.getConversationLimit());
            }
        } catch (Exception ex) {
            log.warn("Failed to record conversation for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    /** Story 7.3 (Task 2). {@code limit} null = unlimited. */
    public void checkAndAlert(String tenantId, long currentCount, Integer limit) {
        if (limit == null || limit <= 0) {
            return;
        }
        UUID tenant = UUID.fromString(tenantId);
        if (currentCount >= limit) {
            redisTemplate.opsForValue().set(BillingRedisKeys.limitReached(tenantId), "1", MARKER_TTL);
            if (firstTime(BillingRedisKeys.alert100(tenantId))) {
                subscriptionService.notifyOwner(tenant, String.format(ALERT_100_MESSAGE, limit));
                log.info("Tenant {} reached its monthly conversation limit ({})", tenantId, limit);
            }
        } else if (currentCount * 100 >= (long) limit * 80 && firstTime(BillingRedisKeys.alert80(tenantId))) {
            subscriptionService.notifyOwner(tenant, String.format(ALERT_80_MESSAGE, currentCount, limit));
        }
    }

    private boolean firstTime(String markerKey) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(markerKey, "sent", MARKER_TTL));
    }
}
