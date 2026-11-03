package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.SubscriptionService;
import com.aireceptionist.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story 7.1 (AC1, AC2, AC4, AC5) / 7.3 (AC5). */
@AutoConfigureMockMvc
class SubscriptionStatusTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubscriptionRepository repository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider tokenProvider;

    private String token(UUID tenantId, String tier) throws Exception {
        return "Bearer " + tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", tier);
    }

    @Test
    void subscriptionEndpointReturnsTierStatusAndLiveUsage() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.syncUsage(12, Instant.now());
        s.resetUsage(Instant.parse("2026-12-03T00:00:00Z"), Instant.now());
        s.syncUsage(12, Instant.now());
        repository.save(s);
        redisTemplate.opsForValue().set("tenant:" + tenantId + ":conv:month", "57");

        mockMvc.perform(get("/v1/tenants/" + tenantId + "/subscription").header("Authorization", token(tenantId, "BASIC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier").value("BASIC"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.conversationUsedThisMonth").value(57))
                .andExpect(jsonPath("$.data.conversationLimit").value(300))
                .andExpect(jsonPath("$.data.nextBillingDate").exists());

        redisTemplate.delete("tenant:" + tenantId + ":conv:month");
        mockMvc.perform(get("/v1/tenants/" + tenantId + "/subscription").header("Authorization", token(tenantId, "BASIC")))
                .andExpect(jsonPath("$.data.conversationUsedThisMonth").value(12));
    }

    @Test
    void otherTenantsSubscriptionIsForbidden() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        UUID other = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");

        mockMvc.perform(get("/v1/tenants/" + tenantId + "/subscription").header("Authorization", token(other, "BASIC")))
                .andExpect(status().isForbidden());
    }

    @Test
    void basicJwtIsRejectedFromVoiceFeatureWithTierUpgradeRequired() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");

        mockMvc.perform(post("/v1/voice/calls/CA-1/transfer").header("Authorization", token(tenantId, "BASIC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TIER_UPGRADE_REQUIRED"));
    }

    @Test
    void tierChangeUpdatesSubscriptionAndTenantTier() {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");

        subscriptionService.updateTierFromWebhook(tenantId, SubscriptionTier.PRO);

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(s.getConversationLimit()).isNull();
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("PRO");
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "SUBSCRIPTION_TIER_CHANGED")).isEqualTo(1);
    }
}
