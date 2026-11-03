package com.aireceptionist.billing;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.billing.service.RazorpayWebhookService;
import com.aireceptionist.billing.service.SubscriptionService;
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 7.2 (AC2-AC6) / 7.4 (AC3) + code-review fixes: verification, idempotency, ordering, dispatch. */
class RazorpayWebhookServiceTest {

    private static final String SECRET = "whsec";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final SubscriptionRepository repository = mock(SubscriptionRepository.class);
    private final GetTenantStatusUseCase tenantStatus = mock(GetTenantStatusUseCase.class);
    private final RazorpayWebhookService service = new RazorpayWebhookService(SECRET, new ObjectMapper(), redisTemplate,
            subscriptionService, repository, tenantStatus);

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(subscriptionService.load(tenantId)).thenReturn(Subscription.basic(tenantId, Instant.now()));
        when(tenantStatus.getStatus(tenantId)).thenReturn(Optional.of("LIVE"));
        when(repository.advanceLastEvent(eq(tenantId), any())).thenReturn(true);
    }

    static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String event(String type, UUID tenantId, String subId, String planId, long currentEnd) {
        return event(type, tenantId, subId, planId, currentEnd, Instant.now().getEpochSecond());
    }

    static String event(String type, UUID tenantId, String subId, String planId, long currentEnd, long createdAt) {
        return "{\"entity\":\"event\",\"event\":\"" + type + "\",\"created_at\":" + createdAt
                + ",\"payload\":{\"subscription\":{\"entity\":{\"id\":\"" + subId + "\",\"plan_id\":\"" + planId
                + "\",\"status\":\"active\",\"current_end\":" + currentEnd
                + ",\"notes\":{\"tenant_id\":\"" + tenantId + "\"}}}}}";
    }

    private RazorpayWebhookService.Result send(String body) throws Exception {
        return service.processWebhook(body.getBytes(StandardCharsets.UTF_8), sign(body, SECRET));
    }

    @Test
    void invalidSignatureIsRejectedWithoutProcessing() {
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        assertThat(service.processWebhook(body.getBytes(), "deadbeef")).isEqualTo(RazorpayWebhookService.Result.INVALID_SIGNATURE);
        assertThat(service.processWebhook(body.getBytes(), null)).isEqualTo(RazorpayWebhookService.Result.INVALID_SIGNATURE);
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void chargedRestoresFirstThenAppliesPlan() throws Exception {
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        assertThat(send(body)).isEqualTo(RazorpayWebhookService.Result.PROCESSED);

        InOrder order = inOrder(subscriptionService);
        order.verify(subscriptionService).restoreTenant(tenantId);
        order.verify(subscriptionService).handlePaymentSuccess(tenantId, "sub_1", "plan_pro_test", Instant.ofEpochSecond(1_900_000_000L));
        verify(valueOps).set(startsWith("razorpay:event:"), eq("processed"), eq(Duration.ofDays(7)));
    }

    @Test
    void haltedStartsGracePeriod() throws Exception {
        send(event("subscription.halted", tenantId, "sub_1", "plan_pro_test", 0));
        verify(subscriptionService).handlePaymentFailure(tenantId);
    }

    @Test
    void cancelledDowngradesInsteadOfStartingGrace() throws Exception {
        send(event("subscription.cancelled", tenantId, "sub_1", "plan_pro_test", 0));
        verify(subscriptionService).handleCancellation(tenantId);
        verify(subscriptionService, never()).handlePaymentFailure(any());
    }

    @Test
    void duplicateBodyIsSkipped() throws Exception {
        when(valueOps.setIfAbsent(startsWith("razorpay:event:"), anyString(), any(Duration.class)))
                .thenReturn(true).thenReturn(false);
        String body = event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L);

        send(body);
        assertThat(send(body)).isEqualTo(RazorpayWebhookService.Result.DUPLICATE);
        verify(subscriptionService, times(1)).restoreTenant(tenantId);
    }

    @Test
    void olderEventThanTheLastAppliedIsIgnored() throws Exception {
        when(repository.advanceLastEvent(eq(tenantId), any())).thenReturn(false);

        assertThat(send(event("subscription.halted", tenantId, "sub_1", "plan_pro_test", 0, 1_700_000_000L)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).handlePaymentFailure(any());
    }

    @Test
    void erasedOrUnknownTenantsAreIgnored() throws Exception {
        when(tenantStatus.getStatus(tenantId)).thenReturn(Optional.of("ERASED"));
        assertThat(send(event("subscription.charged", tenantId, "sub_1", "plan_pro_test", 1_900_000_000L)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);

        when(tenantStatus.getStatus(tenantId)).thenReturn(Optional.empty());
        assertThat(send(event("subscription.charged", tenantId, "sub_2", "plan_pro_test", 1_900_000_001L)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).restoreTenant(any());
    }

    @Test
    void processingFailureIsReportedSoRazorpayRetries() throws Exception {
        doThrow(new IllegalStateException("db down")).when(subscriptionService).handlePaymentFailure(tenantId);

        assertThat(send(event("subscription.halted", tenantId, "sub_1", "plan_pro_test", 0)))
                .isEqualTo(RazorpayWebhookService.Result.FAILED);
        verify(redisTemplate).delete(startsWith("razorpay:event:"));
        verify(valueOps, never()).set(anyString(), eq("processed"), any(Duration.class));
    }

    @Test
    void mismatchedSubscriptionIdIsIgnored() throws Exception {
        Subscription stored = Subscription.basic(tenantId, Instant.now());
        stored.attachRazorpay("sub_REAL", "plan_pro_test", Instant.now());
        when(subscriptionService.load(tenantId)).thenReturn(stored);

        assertThat(send(event("subscription.charged", tenantId, "sub_OTHER", "plan_pro_test", 1_900_000_000L)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).handlePaymentSuccess(any(), any(), any(), any());
    }

    @Test
    void pendingCheckoutChargeIsAcceptedAndItsHaltIgnored() throws Exception {
        Subscription withPending = new Subscription(UUID.randomUUID(), tenantId,
                com.aireceptionist.billing.domain.SubscriptionTier.BASIC,
                com.aireceptionist.billing.domain.SubscriptionStatus.ACTIVE, 0, 300, null, null, null,
                "sub_OLD", "plan_basic_test", "sub_NEW", "plan_pro_test", "https://rzp.io/x", null, null,
                Instant.now(), Instant.now());
        when(subscriptionService.load(tenantId)).thenReturn(withPending);

        assertThat(send(event("subscription.halted", tenantId, "sub_NEW", "plan_pro_test", 0)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).handlePaymentFailure(any());

        assertThat(send(event("subscription.charged", tenantId, "sub_NEW", "plan_pro_test", 1_900_000_000L)))
                .isEqualTo(RazorpayWebhookService.Result.PROCESSED);
        verify(subscriptionService).handlePaymentSuccess(tenantId, "sub_NEW", "plan_pro_test", Instant.ofEpochSecond(1_900_000_000L));
    }

    @Test
    void unknownEventTypesAreIgnored() throws Exception {
        assertThat(send(event("payment.authorized", tenantId, "sub_1", "plan_pro_test", 0)))
                .isEqualTo(RazorpayWebhookService.Result.IGNORED);
        verify(subscriptionService, never()).restoreTenant(any());
    }
}
