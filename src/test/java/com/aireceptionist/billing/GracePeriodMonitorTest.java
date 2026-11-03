package com.aireceptionist.billing;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.service.GracePeriodMonitorJob;
import com.aireceptionist.billing.service.SubscriptionService;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.tenant.port.in.TenantBillingUseCase;
import com.aireceptionist.tenant.port.in.TenantLifecycleUseCase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 7.4 (AC1, AC2, AC4): grace notifications, suspension, 30-day erasure. */
class GracePeriodMonitorTest {

    private final Instant now = Instant.parse("2026-11-03T10:00:00Z");
    private final GetLiveTenantsUseCase liveTenants = mock(GetLiveTenantsUseCase.class);
    private final TenantBillingUseCase tenantBilling = mock(TenantBillingUseCase.class);
    private final TenantLifecycleUseCase lifecycle = mock(TenantLifecycleUseCase.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final GracePeriodMonitorJob job = new GracePeriodMonitorJob(liveTenants, tenantBilling, lifecycle,
            subscriptionService, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID tenantId = UUID.randomUUID();

    private void pastDue(Duration graceRemaining) {
        Subscription s = Subscription.basic(tenantId, now);
        s.startGracePeriod(now.plus(graceRemaining), now);
        when(subscriptionService.load(tenantId)).thenReturn(s);
        when(liveTenants.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(tenantBilling.getTenantIdsWithStatus("PAYMENT_SUSPENDED")).thenReturn(List.of());
    }

    @Test
    void earlyInGraceSendsStartNoticeOnce() {
        pastDue(Duration.ofHours(60));
        job.run();
        verify(subscriptionService).notifyOwnerOnce(eq(tenantId), eq("grace:notified:start:" + tenantId), contains("Grace period ends in 3 days"));
        verify(subscriptionService, never()).suspendTenant(any());
    }

    @Test
    void lastDaySendsTwentyFourHourWarningOnce() {
        pastDue(Duration.ofHours(23));
        job.run();
        verify(subscriptionService).notifyOwnerOnce(eq(tenantId), eq("grace:notified:24h:" + tenantId), contains("24 hours"));
        verify(subscriptionService, never()).suspendTenant(any());
    }

    @Test
    void expiredGraceSuspends() {
        pastDue(Duration.ofMinutes(-1));
        job.run();
        verify(subscriptionService).suspendTenant(tenantId);
    }

    @Test
    void activeSubscriptionIsLeftAlone() {
        when(subscriptionService.load(tenantId)).thenReturn(Subscription.basic(tenantId, now));
        when(liveTenants.getLiveTenantIds()).thenReturn(List.of(tenantId));
        job.run();
        verify(subscriptionService, never()).notifyOwnerOnce(any(), anyString(), anyString());
        verify(subscriptionService, never()).suspendTenant(any());
    }

    @Test
    void thirtyDaysAfterSuspensionTheTenantIsErased() {
        Subscription s = Subscription.basic(tenantId, now);
        s.suspend(now.minus(Duration.ofDays(30)));
        when(subscriptionService.load(tenantId)).thenReturn(s);
        when(tenantBilling.getTenantIdsWithStatus("PAYMENT_SUSPENDED")).thenReturn(List.of(tenantId));

        job.run();

        verify(lifecycle).eraseNow(tenantId);
        verify(subscriptionService).audit(eq(tenantId), eq("PAYMENT_SUSPENSION_ERASED"), anyString());
    }

    @Test
    void recentlySuspendedTenantIsNotErased() {
        Subscription s = Subscription.basic(tenantId, now);
        s.suspend(now.minus(Duration.ofDays(29)));
        when(subscriptionService.load(tenantId)).thenReturn(s);
        when(tenantBilling.getTenantIdsWithStatus("PAYMENT_SUSPENDED")).thenReturn(List.of(tenantId));

        job.run();

        verify(lifecycle, never()).eraseNow(any());
    }
}
