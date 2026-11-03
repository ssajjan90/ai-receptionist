package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.RazorpayGateway;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 7.2 (AC1-AC6) end to end against Postgres/Redis, plus code-review fixes. (Story Task 6
 * named {@code @WebMvcTest}; that slice can't boot in this app — story 6.1's AdminWebConfig finding.)
 */
@AutoConfigureMockMvc
class RazorpayWebhookControllerTest extends AbstractIntegrationTest {

    static final AtomicInteger CREATED = new AtomicInteger();

    @TestConfiguration
    static class FakeRazorpayConfig {
        @Bean
        @Primary
        RazorpayGateway fakeRazorpayGateway() {
            return new RazorpayGateway(new RestTemplate(), new ObjectMapper(), "http://unused", "k", "s") {
                @Override
                public CreatedSubscription createSubscription(String planId, UUID tenantId) {
                    int n = CREATED.incrementAndGet();
                    return new CreatedSubscription("sub_" + tenantId.toString().substring(0, 8) + "_" + n,
                            "https://rzp.io/i/test-" + n);
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

    @BeforeEach
    void reset() {
        CREATED.set(0);
    }

    private ResultActions postWebhook(String body) throws Exception {
        return mockMvc.perform(post("/webhooks/billing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Razorpay-Signature", RazorpayWebhookServiceTest.sign(body, webhookSecret)));
    }

    private ResultActions checkout(UUID tenantId, String planId) throws Exception {
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");
        return mockMvc.perform(post("/v1/tenants/" + tenantId + "/subscription/checkout")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":\"" + planId + "\"}"));
    }

    @Test
    void checkoutReturnsLinkAndRepeatClicksReuseThePendingCheckout() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");

        checkout(tenantId, "plan_pro_test").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://rzp.io/i/test-1"));
        checkout(tenantId, "plan_pro_test").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://rzp.io/i/test-1"));

        assertThat(CREATED.get()).isEqualTo(1);
        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getPendingRazorpaySubscriptionId()).isEqualTo("sub_" + tenantId.toString().substring(0, 8) + "_1");
        assertThat(s.getRazorpaySubscriptionId()).isNull();
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.BASIC); // tier only changes when paid
    }

    @Test
    void checkoutIsRefusedWhileAPaidSubscriptionIsActive() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.attachRazorpay("sub_live", "plan_pro_test", Instant.now());
        repository.save(s);

        checkout(tenantId, "plan_pro_test")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUBSCRIPTION_ALREADY_ACTIVE"));
        assertThat(CREATED.get()).isZero();
    }

    @Test
    void checkoutRejectsUnknownPlan() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        checkout(tenantId, "plan_made_up").andExpect(status().isBadRequest());
    }

    @Test
    void firstChargeOfAPendingCheckoutActivatesProAndDuplicateHasNoEffect() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        checkout(tenantId, "plan_pro_test").andExpect(status().isOk());
        String subId = "sub_" + tenantId.toString().substring(0, 8) + "_1";
        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, subId, "plan_pro_test", 1_900_000_000L);

        postWebhook(body).andExpect(status().isOk());

        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getTier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getRazorpaySubscriptionId()).isEqualTo(subId);
        assertThat(s.getPendingRazorpaySubscriptionId()).isNull();
        assertThat(s.getNextBillingDate()).isEqualTo(Instant.ofEpochSecond(1_900_000_000L));
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("PRO");
        int audits = BillingTestSupport.auditCount(jdbcTemplate, tenantId, "SUBSCRIPTION_TIER_CHANGED");

        jdbcTemplate.update("UPDATE tenants SET tier = 'BASIC' WHERE id = ?", tenantId);
        postWebhook(body).andExpect(status().isOk()); // same signed body → duplicate

        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "SUBSCRIPTION_TIER_CHANGED")).isEqualTo(audits);
    }

    @Test
    void invalidSignatureReturns200ButChangesNothing() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "LIVE");
        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_abc", "plan_pro_test", 1_900_000_000L);

        mockMvc.perform(post("/webhooks/billing").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", "not-a-signature"))
                .andExpect(status().isOk());

        assertThat(repository.findByTenantId(tenantId)).isEmpty();
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
    }

    @Test
    void haltedStartsGraceAndAnOlderHaltAfterAChargeIsIgnored() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        long t0 = Instant.now().getEpochSecond();
        Instant before = Instant.now();

        postWebhook(RazorpayWebhookServiceTest.event("subscription.halted", tenantId, "sub_h", "plan_pro_test", 0, t0))
                .andExpect(status().isOk());
        Subscription s = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(s.getGracePeriodEndsAt()).isBetween(before.plusSeconds(3 * 86400 - 5), Instant.now().plusSeconds(3 * 86400 + 5));
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_GRACE_STARTED")).isEqualTo(1);

        postWebhook(RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_h", "plan_pro_test", 1_900_000_000L, t0 + 60))
                .andExpect(status().isOk());
        // A delayed retry of an older halt must not push the paid tenant back into grace.
        postWebhook(RazorpayWebhookServiceTest.event("subscription.halted", tenantId, "sub_h", "plan_pro_test", 0, t0 + 30))
                .andExpect(status().isOk());

        assertThat(repository.findByTenantId(tenantId).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void cancellationDowngradesToBasicAndKeepsTheTenantLive() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.changeTier(SubscriptionTier.PRO, Instant.now());
        s.attachRazorpay("sub_c", "plan_pro_test", Instant.now());
        s.resetUsage(Instant.now().plus(Duration.ofDays(5)), Instant.now());
        s.syncUsage(900, Instant.now());
        repository.save(s);

        postWebhook(RazorpayWebhookServiceTest.event("subscription.cancelled", tenantId, "sub_c", "plan_pro_test", 0))
                .andExpect(status().isOk());

        Subscription after = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(after.getTier()).isEqualTo(SubscriptionTier.BASIC);
        assertThat(after.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(after.getConversationLimit()).isEqualTo(300);
        assertThat(after.getConversationUsedThisMonth()).isZero(); // fresh month on the limited plan
        assertThat(after.getRazorpaySubscriptionId()).isNull();
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "status")).isEqualTo("LIVE");
    }

    @Test
    void webhookForErasedTenantIsIgnored() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "BASIC", "ERASED");

        postWebhook(RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_e", "plan_pro_test", 1_900_000_000L))
                .andExpect(status().isOk());

        assertThat(repository.findByTenantId(tenantId)).isEmpty();
        assertThat(BillingTestSupport.tenantColumn(jdbcTemplate, tenantId, "tier")).isEqualTo("BASIC");
    }

    @Test
    void oversizedBodyIsRejectedBeforeProcessing() throws Exception {
        mockMvc.perform(post("/webhooks/billing").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[300 * 1024]).header("X-Razorpay-Signature", "x"))
                .andExpect(status().isPayloadTooLarge());
    }
}
