package com.aireceptionist.billing;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.service.RazorpayWebhookService;
import com.aireceptionist.billing.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 7.2 (AC2-AC6) / 7.4 (AC3): webhook verification, idempotency and dispatch. */
class RazorpayWebhookServiceTest {

    private static final String SECRET = "whsec";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final RazorpayWebhookService service =
            new RazorpayWebhookService(SECRET, new ObjectMapper(), redisTemplate, subscriptionService);

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(subscriptionService.load(tenantId)).thenReturn(Subscription.basic(tenantId, Instant.now()));
    }

    static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String event(String type, UUID tenantId, String subId, String planId, long currentEnd) {
        return """
                {"entity":"event","event":"%s","payload":{"subscription":{"entity":{
                  "id":"%s","plan_id":"%s","status":"active","current_end":%d,
                  "notes":{"tenant_id":"%s"}}}}}""".formatted(type, subId, planId, currentEnd, tenantId);
    }

    @Test
    void invalidSignatureIsRejectedWithoutProcessing() {
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        assertThat(service.processWebhook(body.getBytes(), "deadbeef", "evt_1"))
                .isEqualTo(RazorpayWebhookService.Result.INVALID_SIGNATURE);
        verifyNoInteractions(subscriptionService);
        assertThat(service.processWebhook(body.getBytes(), null, "evt_1"))
                .isEqualTo(RazorpayWebhookService.Result.INVALID_SIGNATURE);
    }

    @Test
    void chargedRestoresFirstThenAppliesPlan() throws Exception {
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        assertThat(service.processWebhook(body.getBytes(), sign(body, SECRET), "evt_1"))
                .isEqualTo(RazorpayWebhookService.Result.PROCESSED);

        InOrder order = inOrder(subscriptionService);
        order.verify(subscriptionService).restoreTenant(tenantId);
        order.verify(subscriptionService).handlePaymentSuccess(tenantId, "plan_pro_test", Instant.ofEpochSecond(1_900_000_000L));
        verify(valueOps).set("razorpay:event:evt_1", "processed", Duration.ofDays(7));
    }

    @Test
    void haltedAndCancelledStartGracePeriod() throws Exception {
        for (String type : new String[]{"subscription.halted", "subscription.cancelled"}) {
            String body = event(type, tenantId, "sub_1", "plan_pro_test", 0);
            service.processWebhook(body.getBytes(), sign(body, SECRET), "evt-" + type);
        }
        verify(subscriptionService, org.mockito.Mockito.times(2)).handlePaymentFailure(tenantId);
    }

    @Test
    void duplicateEventIdIsSkipped() throws Exception {
        when(valueOps.setIfAbsent(eq("razorpay:event:evt_dup"), anyString(), any(Duration.class))).thenReturn(false);
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        assertThat(service.processWebhook(body.getBytes(), sign(body, SECRET), "evt_dup"))
                .isEqualTo(RazorpayWebhookService.Result.DUPLICATE);
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void processingFailureReleasesIdempotencyKeySoRetryCanRun() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(subscriptionService).handlePaymentFailure(tenantId);
        String body = event("subscription.halted", tenantId, "sub_1", "plan_pro_test", 0);

        service.processWebhook(body.getBytes(), sign(body, SECRET), "evt_fail");

        verify(redisTemplate).delete("razorpay:event:evt_fail");
        verify(valueOps, never()).set(eq("razorpay:event:evt_fail"), eq("processed"), any(Duration.class));
    }

    @Test
    void mismatchedSubscriptionIdIsIgnored() throws Exception {
        Subscription stored = Subscription.basic(tenantId, Instant.now());
        stored.attachRazorpay("sub_REAL", "plan_pro_test", Instant.now());
        when(subscriptionService.load(tenantId)).thenReturn(stored);
        String body = event("subscription.charged", tenantId, "sub_OTHER", "plan_pro_test", 1_900_000_000L);

        assertThat(service.processWebhook(body.getBytes(), sign(body, SECRET), "evt_x"))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).handlePaymentSuccess(any(), anyString(), any());
    }

    @Test
    void unknownEventTypesAreIgnored() throws Exception {
        String body = event("payment.authorized", tenantId, "sub_1", "plan_pro_test", 0);
        assertThat(service.processWebhook(body.getBytes(), sign(body, SECRET), "evt_y"))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).restoreTenant(any());
    }
}
