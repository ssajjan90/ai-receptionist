package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.ConversationCountSyncJob;
import com.aireceptionist.common.billing.ConversationUsagePort;
import com.aireceptionist.tenant.port.out.SubscriptionProvisioningPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 7.3 (AC1-AC4) + code-review fixes: per-customer-per-day counting, DB sync, monthly reset. */
class ConversationCountSyncTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubscriptionRepository repository;
    @Autowired ConversationCountSyncJob syncJob;
    @Autowired ConversationUsagePort usagePort;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired SubscriptionProvisioningPort provisioning;

    private static String phone(int i) {
        return "+9198" + String.format("%08d", i);
    }

    @Test
    void registrationProvisionsBasicWithThe300Limit() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        provisioning.provisionBasicSubscription(tenantId);

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.BASIC);
        assertThat(s.getConversationLimit()).isEqualTo(300);
    }

    @Test
    void tenCustomersAreTenConversationsAndRepeatsCountOnce() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        for (int i = 0; i < 10; i++) {
            usagePort.recordConversation(tenantId.toString(), phone(i));
        }
        for (int i = 0; i < 5; i++) {
            usagePort.recordConversation(tenantId.toString(), phone(0)); // same customer, same day
        }

        syncJob.run();

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getConversationUsedThisMonth()).isEqualTo(10);
        assertThat(s.getNextBillingDate()).isAfter(Instant.now());
    }

    @Test
    void reachingTheLimitBlocksNewCustomersAndRenewalResetsEverything() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String tenant = tenantId.toString();
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.resetUsage(Instant.now().plus(Duration.ofDays(10)), Instant.now());
        s.syncUsage(299, Instant.now());
        repository.save(s);

        usagePort.recordConversation(tenant, phone(1)); // seeded from DB 299 → 300

        assertThat(usagePort.isLimitReached(tenant, phone(2))).isTrue();
        assertThat(usagePort.isLimitReached(tenant, phone(1))).isFalse(); // already being served today

        // Billing date passes → new month.
        Subscription due = repository.findByTenantId(tenantId).orElseThrow();
        due.resetUsage(Instant.now().minusSeconds(60), Instant.now());
        due.syncUsage(300, Instant.now());
        repository.save(due);

        syncJob.run();

        Subscription renewed = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(renewed.getConversationUsedThisMonth()).isZero();
        assertThat(renewed.getNextBillingDate()).isAfter(Instant.now());
        assertThat(usagePort.isLimitReached(tenant, phone(2))).isFalse();
        assertThat(redisTemplate.hasKey("tenant:" + tenant + ":conv:month")).isFalse();
    }

    @Test
    void usageSyncNeverOverwritesBillingState() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.changeTier(SubscriptionTier.PRO, Instant.now());
        s.resetUsage(Instant.now().plus(Duration.ofDays(10)), Instant.now());
        repository.save(s);
        redisTemplate.opsForValue().set("tenant:" + tenantId + ":conv:month", "7");
        // A webhook moves the subscription into grace between the job's read and write — the job
        // only writes the usage column, so the grace state survives.
        repository.startGrace(tenantId, Instant.now().plus(Duration.ofDays(3)), Instant.now());

        syncJob.run();

        Subscription after = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(after.getConversationUsedThisMonth()).isEqualTo(7);
        assertThat(after.getStatus().name()).isEqualTo("PAST_DUE");
        assertThat(after.getTier()).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void unlimitedPlanCountsButNeverBlocks() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.changeTier(SubscriptionTier.PRO, Instant.now());
        s.syncUsage(5000, Instant.now());
        repository.save(s);

        usagePort.recordConversation(tenantId.toString(), phone(3));

        assertThat(usagePort.isLimitReached(tenantId.toString(), phone(4))).isFalse();
        assertThat(redisTemplate.opsForValue().get("tenant:" + tenantId + ":conv:month")).isEqualTo("5001");
    }
}
