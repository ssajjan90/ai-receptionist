package com.aireceptionist.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Service
public class TenantRateLimitService {

    private final LettuceBasedProxyManager<byte[]> proxyManager;

    @Value("${app.rate-limit.capacity:100}")
    private long capacity;

    @Value("${app.rate-limit.refill-tokens:100}")
    private long refillTokens;

    @Value("${app.rate-limit.refill-period-minutes:1}")
    private long refillPeriodMinutes;

    public TenantRateLimitService(LettuceBasedProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    public boolean tryConsume(String tenantId) {
        byte[] key = ("ratelimit:" + tenantId).getBytes(StandardCharsets.UTF_8);
        Bucket bucket = proxyManager.builder().build(key, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofMinutes(refillPeriodMinutes))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }

    /**
     * Story 5.4 (AC3): admin-notification throttling — a fixed 10-per-minute limit per admin,
     * deliberately not tied to the {@code app.rate-limit.*} properties above (those govern
     * per-tenant API throttling, an unrelated concern).
     */
    public boolean tryConsumeAdminNotification(UUID adminId) {
        byte[] key = ("ratelimit:admin:" + adminId + ":notify").getBytes(StandardCharsets.UTF_8);
        Bucket bucket = proxyManager.builder().build(key, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(10)
                        .refillGreedy(10, Duration.ofMinutes(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }
}
