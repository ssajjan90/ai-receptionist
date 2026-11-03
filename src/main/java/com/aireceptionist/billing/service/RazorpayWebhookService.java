package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.repository.SubscriptionRepository;
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
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
import java.util.Set;
import java.util.UUID;

/**
 * Story 7.2 (AC2-AC6) / 7.4 (AC3), with code-review fixes.
 * <ul>
 *   <li>Signature: hex(HMAC-SHA256(webhookSecret, rawBody)) vs {@code X-Razorpay-Signature}, constant-time.</li>
 *   <li>Idempotency on the SHA-256 of the <em>signed</em> body (the unsigned event-id header can be
 *       swapped to replay a captured body). Claimed with SETNX before processing; kept 7 days
 *       (Razorpay retries for up to 24h); released on failure and reported as {@link Result#FAILED}
 *       so the controller answers 5xx and Razorpay retries.</li>
 *   <li>Ordering: the event's {@code created_at} must be ≥ the last applied event's, else ignored
 *       (a delayed {@code halted} can't undo a newer {@code charged}).</li>
 *   <li>Tenant: {@code notes.tenant_id} (set at checkout), cross-checked against the live or pending
 *       Razorpay subscription id; events for ERASED/TERMINATED/unknown tenants are ignored.</li>
 *   <li>{@code subscription.cancelled} downgrades to BASIC (code-review decision);
 *       {@code subscription.halted} starts the grace period.</li>
 * </ul>
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);
    static final Duration PROCESSED_TTL = Duration.ofDays(7);
    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(5);
    private static final Set<String> IGNORED_TENANT_STATUSES = Set.of("ERASED", "TERMINATED");

    public enum Result { PROCESSED, DUPLICATE, INVALID_SIGNATURE, IGNORED, FAILED }

    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository repository;
    private final GetTenantStatusUseCase tenantStatus;

    public RazorpayWebhookService(@Value("${app.razorpay.webhook-secret}") String webhookSecret,
                                  ObjectMapper objectMapper,
                                  StringRedisTemplate redisTemplate,
                                  SubscriptionService subscriptionService,
                                  SubscriptionRepository repository,
                                  GetTenantStatusUseCase tenantStatus) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.subscriptionService = subscriptionService;
        this.repository = repository;
        this.tenantStatus = tenantStatus;
    }

    public Result processWebhook(byte[] rawBody, String signature) {
        if (!verifySignature(rawBody, signature)) {
            log.warn("Razorpay webhook signature verification failed");
            return Result.INVALID_SIGNATURE;
        }
        String key = BillingRedisKeys.razorpayEvent(sha256(rawBody));
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "processing", IN_FLIGHT_TTL))) {
            log.info("Duplicate Razorpay event ignored");
            return Result.DUPLICATE;
        }
        try {
            Result result = dispatch(objectMapper.readTree(rawBody));
            redisTemplate.opsForValue().set(key, "processed", PROCESSED_TTL);
            return result;
        } catch (Exception ex) {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
                // the in-flight marker expires on its own
            }
            log.error("Failed to process Razorpay event — Razorpay will retry: {}", ex.getMessage(), ex);
            return Result.FAILED;
        }
    }

    private Result dispatch(JsonNode event) {
        String type = event.path("event").asText("");
        if (!Set.of("subscription.charged", "subscription.halted", "subscription.cancelled").contains(type)) {
            log.debug("Ignoring Razorpay event type {}", type);
            return Result.IGNORED;
        }
        JsonNode sub = event.path("payload").path("subscription").path("entity");
        String razorpaySubscriptionId = sub.path("id").asText(null);
        Optional<Resolved> resolved = resolveTenant(sub, razorpaySubscriptionId);
        if (resolved.isEmpty()) {
            return Result.IGNORED;
        }
        UUID tenantId = resolved.get().tenantId();
        boolean isPending = resolved.get().pending();

        // The ordering guard is an UPDATE — the row must exist, or the very first event for a
        // pre-Epic-7 tenant would look "out of order" and be dropped.
        repository.ensureExists(tenantId, Instant.now());
        if (event.hasNonNull("created_at")
                && !repository.advanceLastEvent(tenantId, Instant.ofEpochSecond(event.path("created_at").asLong()))) {
            log.info("Ignoring out-of-order Razorpay {} for tenant={} (a newer event was already applied)", type, tenantId);
            return Result.IGNORED;
        }

        switch (type) {
            case "subscription.charged" -> handleSubscriptionCharged(tenantId, razorpaySubscriptionId,
                    sub.path("plan_id").asText(null),
                    sub.hasNonNull("current_end") ? Instant.ofEpochSecond(sub.path("current_end").asLong()) : null);
            case "subscription.halted" -> {
                if (isPending) {
                    return Result.IGNORED; // a never-paid checkout can't fail a payment
                }
                subscriptionService.handlePaymentFailure(tenantId);
            }
            default -> { // subscription.cancelled
                if (isPending) {
                    repository.clearPending(tenantId, razorpaySubscriptionId, Instant.now());
                } else {
                    subscriptionService.handleCancellation(tenantId);
                }
            }
        }
        return Result.PROCESSED;
    }

    /** Story 7.4 Task 4: restore access first, then apply the (possibly new) plan. */
    void handleSubscriptionCharged(UUID tenantId, String razorpaySubscriptionId, String planId, Instant currentEnd) {
        subscriptionService.restoreTenant(tenantId);
        subscriptionService.handlePaymentSuccess(tenantId, razorpaySubscriptionId, planId, currentEnd);
    }

    private record Resolved(UUID tenantId, boolean pending) {
    }

    private Optional<Resolved> resolveTenant(JsonNode sub, String razorpaySubscriptionId) {
        String tenantIdText = sub.path("notes").path("tenant_id").asText(null);
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantIdText);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("Razorpay subscription {} has no valid notes.tenant_id", razorpaySubscriptionId);
            return Optional.empty();
        }
        Optional<String> status = tenantStatus.getStatus(tenantId);
        if (status.isEmpty() || IGNORED_TENANT_STATUSES.contains(status.get())) {
            log.warn("Ignoring Razorpay event for tenant {} in status {}", tenantId, status.orElse("<none>"));
            return Optional.empty();
        }
        Subscription stored = subscriptionService.load(tenantId);
        if (razorpaySubscriptionId != null && razorpaySubscriptionId.equals(stored.getPendingRazorpaySubscriptionId())) {
            return Optional.of(new Resolved(tenantId, true));
        }
        if (stored.getRazorpaySubscriptionId() != null && razorpaySubscriptionId != null
                && !stored.getRazorpaySubscriptionId().equals(razorpaySubscriptionId)) {
            log.warn("Razorpay subscription {} does not match tenant {}'s subscription {} — ignored",
                    razorpaySubscriptionId, tenantId, stored.getRazorpaySubscriptionId());
            return Optional.empty();
        }
        return Optional.of(new Resolved(tenantId, false));
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
