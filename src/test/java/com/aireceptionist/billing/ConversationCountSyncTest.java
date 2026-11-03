package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.ConversationCountSyncJob;
import com.aireceptionist.common.billing.ConversationUsagePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 7.3 (AC2-AC4): Redis counting, DB sync and the monthly reset. */
class ConversationCountSyncTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubscriptionRepository repository;
    @Autowired ConversationCountSyncJob syncJob;
    @Autowired ConversationUsagePort usagePort;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void tenConversationsAreSyncedToTheDatabase() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        for (int i = 0; i < 10; i++) {
            usagePort.recordConversation(tenantId.toString());
        }

        syncJob.run();

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getConversationUsedThisMonth()).isEqualTo(10);
        assertThat(s.getNextBillingDate()).isAfter(Instant.now());
    }

    @Test
    void reachingTheLimitBlocksAndRenewalResetsEverything() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String tenant = tenantId.toString();
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.resetUsage(Instant.now().plus(Duration.ofDays(10)), Instant.now());
        s.syncUsage(299, Instant.now());
        repository.save(s);

        usagePort.recordConversation(tenant); // seeded from DB 299 → 300

        assertThat(usagePort.isLimitReached(tenant)).isTrue();

        // Billing date passes → new month.
        Subscription due = repository.findByTenantId(tenantId).orElseThrow();
        due.resetUsage(Instant.now().minusSeconds(60), Instant.now());
        due.syncUsage(300, Instant.now());
        repository.save(due);

        syncJob.run();

        Subscription renewed = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(renewed.getConversationUsedThisMonth()).isZero();
        assertThat(renewed.getNextBillingDate()).isAfter(Instant.now());
        assertThat(usagePort.isLimitReached(tenant)).isFalse();
        assertThat(redisTemplate.hasKey("tenant:" + tenant + ":conv:month")).isFalse();
    }

    @Test
    void unlimitedPlanCountsButNeverBlocks() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.changeTier(com.aireceptionist.billing.domain.SubscriptionTier.PRO, Instant.now());
        s.syncUsage(5000, Instant.now());
        repository.save(s);

        usagePort.recordConversation(tenantId.toString());

        assertThat(usagePort.isLimitReached(tenantId.toString())).isFalse();
        assertThat(redisTemplate.opsForValue().get("tenant:" + tenantId + ":conv:month")).isEqualTo("5001");
    }
}
