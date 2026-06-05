package com.aireceptionist.ratelimit;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ratelimit.TenantRateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "app.rate-limit.capacity=3",
        "app.rate-limit.refill-tokens=3",
        "app.rate-limit.refill-period-minutes=1"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TenantRateLimitService rateLimitService;

    @Test
    void tenantBlockedAfterExceedingThreshold() {
        String tenantId = UUID.randomUUID().toString();

        assertThat(rateLimitService.tryConsume(tenantId)).isTrue();  // 1
        assertThat(rateLimitService.tryConsume(tenantId)).isTrue();  // 2
        assertThat(rateLimitService.tryConsume(tenantId)).isTrue();  // 3
        assertThat(rateLimitService.tryConsume(tenantId)).isFalse(); // 4 — blocked
    }
}
