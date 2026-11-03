package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.RazorpayGateway;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 7.2 (AC1-AC6) end to end against Postgres/Redis. Story Task 6 named {@code @WebMvcTest};
 * that slice can't boot in this app (story 6.1's AdminWebConfig finding), so this is the
 * codebase's established AbstractIntegrationTest + MockMvc style.
 */
@AutoConfigureMockMvc
class RazorpayWebhookControllerTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeRazorpayConfig {
        @Bean
        @Primary
        RazorpayGateway fakeRazorpayGateway() {
            return new RazorpayGateway(new RestTemplate(), new ObjectMapper(), "http://unused", "k", "s") {
                @Override
                public CreatedSubscription createSubscription(String planId, UUID tenantId) {
                    return new CreatedSubscription("sub_" + tenantId.toString().substring(0, 8), "https://rzp.io/i/test");
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubscriptionRepository repository;
    @Autowired JwtTokenProvider tokenProvider;

    @Value("${app.razorpay.webhook-secret}")
    String webhookSecret;

    private void postWebhook(String body, String eventId, String signature) throws Exception {
        mockMvc.perform(post("/webhooks/billing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", eventId))
                .andExpect(status().isOk());
    }

    @Test
    void checkoutCreatesRazorpaySubscriptionAndReturnsCheckoutUrl() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/subscription/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"plan_pro_test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://rzp.io/i/test"));

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getRazorpaySubscriptionId()).isEqualTo("sub_" + tenantId.toString().substring(0, 8));
        assertThat(s.getRazorpayPlanId()).isEqualTo("plan_pro_test");
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.BASIC); // tier only changes when paid
    }

    @Test
    void checkoutRejectsUnknownPlan() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/subscription/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"plan_made_up\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validChargedWebhookUpgradesTierAndDuplicateHasNoEffect() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String eventId = "evt_" + UUID.randomUUID();
        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_abc", "plan_pro_test", 1_900_000_000L);

        postWebhook(body, eventId, RazorpayWebhookServiceTest.sign(body, webhookSecret));

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getNextBillingDate()).isEqualTo(Instant.ofEpochSecond(1_900_000_000L));
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("PRO");
        int auditsAfterFirst = BillingTestSupport.auditCount(jdbcTemplate, tenantId, "SUBSCRIPTION_TIER_CHANGED");

        // Downgrade by other means, then replay the same event id: must not re-apply.
        jdbcTemplate.update("UPDATE tenants SET tier = 'BASIC' WHERE id = ?", tenantId);
        postWebhook(body, eventId, RazorpayWebhookServiceTest.sign(body, webhookSecret));

        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "SUBSCRIPTION_TIER_CHANGED")).isEqualTo(auditsAfterFirst);
    }

    @Test
    void invalidSignatureReturns200ButChangesNothing() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_abc", "plan_pro_test", 1_900_000_000L);

        postWebhook(body, "evt_" + UUID.randomUUID(), "not-a-signature");

        assertThat(repository.findByTenantId(tenantId)).isEmpty();
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
    }

    @Test
    void haltedWebhookStartsThreeDayGracePeriod() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        String body = RazorpayWebhookServiceTest.event("subscription.halted", tenantId, "sub_h", "plan_pro_test", 0);
        Instant before = Instant.now();

        postWebhook(body, "evt_" + UUID.randomUUID(), RazorpayWebhookServiceTest.sign(body, webhookSecret));

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(s.getGracePeriodEndsAt()).isBetween(before.plusSeconds(3 * 86400 - 5), Instant.now().plusSeconds(3 * 86400 + 5));
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_GRACE_STARTED")).isEqualTo(1);
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "status")).isEqualTo("LIVE");
    }
}
