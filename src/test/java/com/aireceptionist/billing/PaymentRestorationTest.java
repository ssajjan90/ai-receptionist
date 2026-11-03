package com.aireceptionist.billing;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.GracePeriodMonitorJob;
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 7.4 (AC1, AC3, AC5). "AI pipeline resumes" is asserted via the tenant status the
 * WhatsApp/voice guards read (LIVE); the WhatsApp guard itself is unit-tested in
 * WhatsAppMessageServiceTest — the async-listener end-to-end path is the environment-wide
 * pre-existing failure documented since story 6.1 (AiPipelineTest).
 */
@AutoConfigureMockMvc
class PaymentRestorationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubscriptionRepository repository;
    @Autowired GracePeriodMonitorJob graceMonitor;
    @Autowired GetTenantStatusUseCase tenantStatus;

    @Value("${app.razorpay.webhook-secret}")
    String webhookSecret;

    @Test
    void expiredGraceSuspendsThenPaymentRestoresToLive() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "LIVE");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.startGracePeriod(Instant.now().minus(Duration.ofMinutes(5)), Instant.now());
        repository.save(s);

        graceMonitor.run();

        assertThat(tenantStatus.getStatus(tenantId)).contains("PAYMENT_SUSPENDED");
        assertThat(repository.findByTenantId(tenantId).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_SUSPENSION")).isEqualTo(1);

        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_r", "plan_pro_test", 1_900_000_000L);
        mockMvc.perform(post("/webhooks/billing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Razorpay-Signature", RazorpayWebhookServiceTest.sign(body, webhookSecret)))
                .andExpect(status().isOk());

        assertThat(tenantStatus.getStatus(tenantId)).contains("LIVE");
        Subscription restored = repository.findByTenantId(tenantId).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(restored.getSuspendedAt()).isNull();
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_RESTORED")).isEqualTo(1);
    }

    @Test
    void paymentNeverLiftsAnAdminSuspension() throws Exception {
        UUID tenantId = BillingTestSupport.seedTenant(jdbcTemplate, "PRO", "SUSPENDED");
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.startGracePeriod(Instant.now().plus(Duration.ofDays(2)), Instant.now());
        repository.save(s);
        String body = RazorpayWebhookServiceTest.event("subscription.charged", tenantId, "sub_a", "plan_pro_test", 1_900_000_000L);

        mockMvc.perform(post("/webhooks/billing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Razorpay-Signature", RazorpayWebhookServiceTest.sign(body, webhookSecret)))
                .andExpect(status().isOk());

        assertThat(tenantStatus.getStatus(tenantId)).contains("SUSPENDED");
        assertThat(repository.findByTenantId(tenantId).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        // Owner is told the payment arrived, not that service is back (code review).
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_RECEIVED")).isEqualTo(1);
        assertThat(BillingTestSupport.auditCount(jdbcTemplate, tenantId, "PAYMENT_RESTORED")).isZero();
    }
}
