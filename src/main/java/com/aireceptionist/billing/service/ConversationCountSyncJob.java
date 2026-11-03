package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Story 7.3 (AC3, AC4): every 5 minutes, copy each LIVE tenant's Redis counter into
 * {@code subscriptions.conversation_used_this_month}; on/after the billing renewal date, start a
 * new usage month (Redis + DB counters to 0, limit flag and alert markers cleared). A tenant with
 * no billing date yet (never paid via Razorpay) gets one a month out from first sight.
 */
@Component
public class ConversationCountSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ConversationCountSyncJob.class);

    private final GetLiveTenantsUseCase liveTenants;
    private final SubscriptionService subscriptionService;
    private final com.aireceptionist.billing.repository.SubscriptionRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public ConversationCountSyncJob(GetLiveTenantsUseCase liveTenants, SubscriptionService subscriptionService,
                                    com.aireceptionist.billing.repository.SubscriptionRepository repository,
                                    StringRedisTemplate redisTemplate, Clock clock) {
        this.liveTenants = liveTenants;
        this.subscriptionService = subscriptionService;
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.billing.usage-sync-ms:300000}", initialDelayString = "${app.billing.usage-sync-ms:300000}")
    public void run() {
        Instant now = Instant.now(clock);
        for (UUID tenantId : liveTenants.getLiveTenantIds()) {
            try {
                syncTenant(tenantId, now);
            } catch (Exception ex) {
                log.warn("Usage sync failed for tenant={}: {}", tenantId, ex.getMessage());
            }
        }
    }

    void syncTenant(UUID tenantId, Instant now) {
        Subscription s = subscriptionService.load(tenantId);
        String tenant = tenantId.toString();
        if (s.getNextBillingDate() == null) {
            s.resetUsage(plusMonths(now, 1), now);
            s.syncUsage(liveCount(tenant, s.getConversationUsedThisMonth()), now);
        } else if (!s.getNextBillingDate().isAfter(now)) {
            Instant next = s.getNextBillingDate();
            while (!next.isAfter(now)) {
                next = plusMonths(next, 1);
            }
            s.resetUsage(next, now);
            subscriptionService.resetUsageCounters(tenant);
            log.info("New usage month for tenant={} (next billing {})", tenant, next);
        } else {
            s.syncUsage(liveCount(tenant, s.getConversationUsedThisMonth()), now);
        }
        repository.save(s);
    }

    private int liveCount(String tenantId, int fallback) {
        String value = redisTemplate.opsForValue().get(BillingRedisKeys.conversationCount(tenantId));
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Instant plusMonths(Instant instant, int months) {
        return instant.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
