package com.aireceptionist.billing;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.service.SubscriptionService;
import com.aireceptionist.billing.service.UsageAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 7.3 (AC1-AC3): thresholds, once-only alerts, limit flag. */
class UsageAlertServiceTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final UsageAlertService service = new UsageAlertService(subscriptionService, redisTemplate);

    private final UUID tenantId = UUID.randomUUID();
    private final String tenant = tenantId.toString();
    private final Set<String> markers = new HashSet<>();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Simulate SETNX semantics for alert markers.
        when(valueOps.setIfAbsent(anyString(), eq("sent"), any(Duration.class)))
                .thenAnswer(inv -> markers.add(inv.getArgument(0)));
    }

    @Test
    void eightyPercentAlertFiresOnceAtThreshold() {
        service.checkAndAlert(tenant, 239, 300);
        verify(subscriptionService, never()).notifyOwner(any(), anyString());

        service.checkAndAlert(tenant, 240, 300);
        service.checkAndAlert(tenant, 241, 300);
        service.checkAndAlert(tenant, 260, 300);

        verify(subscriptionService, times(1)).notifyOwner(eq(tenantId), contains("80%"));
        verify(valueOps, never()).set(eq("tenant:" + tenant + ":conv:limit_reached"), anyString(), any(Duration.class));
    }

    @Test
    void hundredPercentAlertsOnceAndSetsBlockFlag() {
        service.checkAndAlert(tenant, 300, 300);
        service.checkAndAlert(tenant, 301, 300);

        verify(subscriptionService, times(1)).notifyOwner(eq(tenantId), contains("used all 300"));
        verify(valueOps, times(2)).set(eq("tenant:" + tenant + ":conv:limit_reached"), eq("1"), any(Duration.class));
    }

    @Test
    void unlimitedPlansNeverAlert() {
        service.checkAndAlert(tenant, 10_000, null);
        verify(subscriptionService, never()).notifyOwner(any(), anyString());
    }

    @Test
    void recordConversationSeedsFromDbAndIncrementsAtomically() {
        Subscription s = Subscription.basic(tenantId, Instant.now());
        s.syncUsage(42, Instant.now());
        when(subscriptionService.load(tenantId)).thenReturn(s);
        when(valueOps.increment("tenant:" + tenant + ":conv:month")).thenReturn(43L);

        service.recordConversation(tenant);

        verify(valueOps).setIfAbsent(eq("tenant:" + tenant + ":conv:month"), eq("42"), any(Duration.class));
        verify(valueOps).increment("tenant:" + tenant + ":conv:month");
    }

    @Test
    void limitCheckReadsFlagAndFailsOpenOnRedisError() {
        when(redisTemplate.hasKey("tenant:" + tenant + ":conv:limit_reached")).thenReturn(true);
        assertThat(service.isLimitReached(tenant)).isTrue();

        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(service.isLimitReached(tenant)).isFalse();
    }
}
