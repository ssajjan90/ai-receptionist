package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.common.billing.ConversationUsagePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Story 7.3 (AC1-AC3) with the code-review decision on the unit: <b>one conversation = one
 * customer phone number per day</b> (Asia/Kolkata), however many messages — matching the PRD's
 * "300 conversations", not one per AI reply. The first metered reply to a customer on a given day
 * claims {@code tenant:{id}:conv:seen:{yyyyMMdd}:{sha256(phone)}} (SETNX) and only then
 * {@code INCR}s the monthly counter. Customers already counted today keep being served after the
 * limit is hit; only <em>new</em> conversations get the "service limit reached" reply.
 *
 * <p>Thresholds use {@code >=} with once-per-month SETNX markers (an exact {@code ==} silently misses
 * the alert if the counter skips a value), kept until the monthly reset.
 */
@Service
public class UsageAlertService implements ConversationUsagePort {

    private static final Logger log = LoggerFactory.getLogger(UsageAlertService.class);
    private static final Duration MARKER_TTL = Duration.ofDays(35);
    private static final Duration SEEN_TTL = Duration.ofDays(2);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    static final String ALERT_80_MESSAGE = "📊 Heads up: you've used %d of your %d monthly AI conversations (80%%). "
            + "Upgrade to Pro for unlimited conversations so your customers keep getting instant replies.";
    static final String ALERT_100_MESSAGE = "🚫 You've used all %d AI conversations included this month. New customer "
            + "conversations now get a 'service limit reached' reply. Upgrade to Pro to resume AI replies immediately.";

    private final SubscriptionService subscriptionService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public UsageAlertService(SubscriptionService subscriptionService, StringRedisTemplate redisTemplate, Clock clock) {
        this.subscriptionService = subscriptionService;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public boolean isLimitReached(String tenantId, String customerPhone) {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(BillingRedisKeys.limitReached(tenantId)))) {
                return false;
            }
            // A conversation already counted today continues.
            return !Boolean.TRUE.equals(redisTemplate.hasKey(seenKey(tenantId, customerPhone)));
        } catch (Exception ex) {
            // Fail open: a Redis outage must not silence every customer.
            log.warn("Usage limit check unavailable for tenant={}: {}", tenantId, ex.getMessage());
            return false;
        }
    }

    @Override
    public void recordConversation(String tenantId, String customerPhone) {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(seenKey(tenantId, customerPhone), "1", SEEN_TTL))) {
                return; // already counted today
            }
            String key = BillingRedisKeys.conversationCount(tenantId);
            Subscription subscription = subscriptionService.load(UUID.fromString(tenantId));
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

    String seenKey(String tenantId, String customerPhone) {
        String day = LocalDate.now(clock.withZone(BUSINESS_ZONE)).toString().replace("-", "");
        return "tenant:" + tenantId + ":conv:seen:" + day + ":" + sha256(customerPhone == null ? "" : customerPhone);
    }

    private boolean firstTime(String markerKey) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(markerKey, "sent", MARKER_TTL));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
