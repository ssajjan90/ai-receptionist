package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 7.2 (AC2-AC6) / 7.4 (AC3).
 * <ul>
 *   <li>Signature: hex(HMAC-SHA256(webhookSecret, rawBody)) vs {@code X-Razorpay-Signature},
 *       constant-time compare — Razorpay's documented scheme.</li>
 *   <li>Idempotency: event id from Razorpay's {@code X-Razorpay-Event-Id} header (falls back to a
 *       body hash). Claimed with SETNX before processing so concurrent retries can't both run;
 *       released on failure so a replay can retry. Kept 7 days, not the Dev Notes' 5 minutes —
 *       Razorpay retries for up to 24h, so a 5-minute marker would let a late retry re-apply (AC6).</li>
 *   <li>Tenant: {@code payload.subscription.entity.notes.tenant_id} (set at checkout), cross-checked
 *       against the stored Razorpay subscription id.</li>
 * </ul>
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);
    static final Duration PROCESSED_TTL = Duration.ofDays(7);
    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(5);

    public enum Result { PROCESSED, DUPLICATE, INVALID_SIGNATURE, IGNORED }

    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionService subscriptionService;

    public RazorpayWebhookService(@Value("${app.razorpay.webhook-secret}") String webhookSecret,
                                  ObjectMapper objectMapper,
                                  StringRedisTemplate redisTemplate,
                                  SubscriptionService subscriptionService) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.subscriptionService = subscriptionService;
    }

    public Result processWebhook(byte[] rawBody, String signature, String eventIdHeader) {
        if (!verifySignature(rawBody, signature)) {
            log.warn("Razorpay webhook signature verification failed");
            return Result.INVALID_SIGNATURE;
        }
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader : "body-" + sha256(rawBody);
        String key = BillingRedisKeys.razorpayEvent(eventId);
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "processing", IN_FLIGHT_TTL))) {
            log.info("Duplicate Razorpay event {} ignored", eventId);
            return Result.DUPLICATE;
        }
        try {
            Result result = dispatch(objectMapper.readTree(rawBody));
            redisTemplate.opsForValue().set(key, "processed", PROCESSED_TTL);
            return result;
        } catch (Exception ex) {
            redisTemplate.delete(key);
            log.error("Failed to process Razorpay event {}: {}", eventId, ex.getMessage(), ex);
            return Result.IGNORED;
        }
    }

    private Result dispatch(JsonNode event) {
        String type = event.path("event").asText("");
        JsonNode sub = event.path("payload").path("subscription").path("entity");
        String razorpaySubscriptionId = sub.path("id").asText(null);
        return switch (type) {
            case "subscription.charged" -> {
                Optional<UUID> tenant = resolveTenant(sub, razorpaySubscriptionId);
                if (tenant.isEmpty()) {
                    yield Result.IGNORED;
                }
                handleSubscriptionCharged(tenant.get(), sub.path("plan_id").asText(null),
                        sub.hasNonNull("current_end") ? Instant.ofEpochSecond(sub.path("current_end").asLong()) : null);
                yield Result.PROCESSED;
            }
            case "subscription.cancelled", "subscription.halted" -> {
                Optional<UUID> tenant = resolveTenant(sub, razorpaySubscriptionId);
                if (tenant.isEmpty()) {
                    yield Result.IGNORED;
                }
                subscriptionService.handlePaymentFailure(tenant.get());
                yield Result.PROCESSED;
            }
            default -> {
                log.debug("Ignoring Razorpay event type {}", type);
                yield Result.IGNORED;
            }
        };
    }

    /** Story 7.4 Task 4: restore access first, then apply the (possibly new) tier. */
    void handleSubscriptionCharged(UUID tenantId, String planId, Instant currentEnd) {
        subscriptionService.restoreTenant(tenantId);
        subscriptionService.handlePaymentSuccess(tenantId, planId, currentEnd);
    }

    private Optional<UUID> resolveTenant(JsonNode sub, String razorpaySubscriptionId) {
        String tenantIdText = sub.path("notes").path("tenant_id").asText(null);
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantIdText);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("Razorpay subscription {} has no valid notes.tenant_id", razorpaySubscriptionId);
            return Optional.empty();
        }
        Subscription stored = subscriptionService.load(tenantId);
        if (stored.getRazorpaySubscriptionId() != null && razorpaySubscriptionId != null
                && !stored.getRazorpaySubscriptionId().equals(razorpaySubscriptionId)) {
            log.warn("Razorpay subscription {} does not match tenant {}'s subscription {} — ignored",
                    razorpaySubscriptionId, tenantId, stored.getRazorpaySubscriptionId());
            return Optional.empty();
        }
        return Optional.of(tenantId);
    }

    boolean verifySignature(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("Unable to verify Razorpay signature: {}", ex.getMessage());
            return false;
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
