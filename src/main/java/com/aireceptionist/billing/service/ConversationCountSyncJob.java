package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 7.3 (AC3, AC4): every 5 minutes copy each LIVE tenant's Redis counter into
 * {@code conversation_used_this_month} (that column only — never billing state), and once the
 * billing date passes start a new usage month.
 *
 * <p>Reset ordering (code review): clear Redis first (if that fails, skip — retry next run, so the
 * DB never shows 0 while Redis still says "limit reached"), then the compare-and-set DB reset, then
 * clear Redis again to drop any counter a concurrent conversation re-seeded from the old DB value.
 * Renewal dates step from a fixed anchor, so the 31st doesn't drift to the 28th.
 */
@Component
public class ConversationCountSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ConversationCountSyncJob.class);

    private final GetLiveTenantsUseCase liveTenants;
    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public ConversationCountSyncJob(GetLiveTenantsUseCase liveTenants, SubscriptionService subscriptionService,
                                    SubscriptionRepository repository, StringRedisTemplate redisTemplate, Clock clock) {
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
        repository.ensureExists(tenantId, now);
        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        String tenant = tenantId.toString();
        if (s.getNextBillingDate() != null && !s.getNextBillingDate().isAfter(now)) {
            subscriptionService.resetUsageCounters(tenant); // throws if Redis is down → retry next run
            Instant anchor = s.getBillingAnchorAt() != null ? s.getBillingAnchorAt() : s.getNextBillingDate();
            if (repository.resetUsage(tenantId, s.getNextBillingDate(), SubscriptionService.nextRenewal(anchor, now), anchor, now)) {
                log.info("New usage month for tenant={}", tenant);
            }
            subscriptionService.resetUsageCounters(tenant);
            return;
        }
        if (s.getNextBillingDate() == null) {
            Instant anchor = s.getBillingAnchorAt() != null ? s.getBillingAnchorAt() : now;
            repository.resetUsage(tenantId, null, SubscriptionService.nextRenewal(anchor, now), anchor, now);
        }
        String live = redisTemplate.opsForValue().get(BillingRedisKeys.conversationCount(tenant));
        if (live != null) {
            repository.syncUsage(tenantId, Integer.parseInt(live));
        }
    }
}
