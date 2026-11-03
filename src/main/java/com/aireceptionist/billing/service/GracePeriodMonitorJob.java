package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.tenant.port.in.TenantBillingUseCase;
import com.aireceptionist.tenant.port.in.TenantLifecycleUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 7.4 (AC1, AC2, AC4), hourly:
 * <ol>
 *   <li>PAST_DUE subscriptions of LIVE tenants: grace-start notice (once), 24h warning (once),
 *       suspension when the grace period has ended.</li>
 *   <li>PAYMENT_SUSPENDED tenants unpaid for 30 days are erased (NFR28) via the tenant module's
 *       {@code eraseNow}. Deviation: lives here rather than in admin's ScheduledDataRetentionJob,
 *       so the admin module doesn't take a dependency on billing's subscription state.</li>
 * </ol>
 */
@Component
public class GracePeriodMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(GracePeriodMonitorJob.class);
    static final Duration WARNING_WINDOW = Duration.ofHours(24);
    static final Duration SUSPENDED_RETENTION = Duration.ofDays(30);
    public static final String AUDIT_PAYMENT_SUSPENSION_ERASED = "PAYMENT_SUSPENSION_ERASED";

    private final GetLiveTenantsUseCase liveTenants;
    private final TenantBillingUseCase tenantBilling;
    private final TenantLifecycleUseCase tenantLifecycle;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public GracePeriodMonitorJob(GetLiveTenantsUseCase liveTenants, TenantBillingUseCase tenantBilling,
                                 TenantLifecycleUseCase tenantLifecycle, SubscriptionService subscriptionService,
                                 Clock clock) {
        this.liveTenants = liveTenants;
        this.tenantBilling = tenantBilling;
        this.tenantLifecycle = tenantLifecycle;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.billing.grace-monitor-cron:0 0 * * * *}")
    public void run() {
        Instant now = Instant.now(clock);
        for (UUID tenantId : liveTenants.getLiveTenantIds()) {
            try {
                checkGrace(tenantId, now);
            } catch (Exception ex) {
                log.warn("Grace check failed for tenant={}: {}", tenantId, ex.getMessage());
            }
        }
        for (UUID tenantId : tenantBilling.getTenantIdsWithStatus("PAYMENT_SUSPENDED")) {
            try {
                eraseIfLongSuspended(tenantId, now);
            } catch (Exception ex) {
                log.error("Post-suspension erasure failed for tenant={}", tenantId, ex);
            }
        }
    }

    void checkGrace(UUID tenantId, Instant now) {
        Subscription s = subscriptionService.load(tenantId);
        if (s.getStatus() != SubscriptionStatus.PAST_DUE || s.getGracePeriodEndsAt() == null) {
            return;
        }
        Duration remaining = Duration.between(now, s.getGracePeriodEndsAt());
        String tenant = tenantId.toString();
        if (remaining.isZero() || remaining.isNegative()) {
            subscriptionService.suspendTenant(tenantId);
        } else if (remaining.compareTo(WARNING_WINDOW) <= 0) {
            subscriptionService.notifyOwnerOnce(tenantId, BillingRedisKeys.grace24hNotified(tenant),
                    SubscriptionService.GRACE_24H_MESSAGE);
        } else {
            subscriptionService.notifyOwnerOnce(tenantId, BillingRedisKeys.graceStartNotified(tenant),
                    SubscriptionService.GRACE_START_MESSAGE);
        }
    }

    void eraseIfLongSuspended(UUID tenantId, Instant now) {
        Subscription s = subscriptionService.load(tenantId);
        if (s.getStatus() == SubscriptionStatus.SUSPENDED && s.getSuspendedAt() != null
                && !s.getSuspendedAt().plus(SUSPENDED_RETENTION).isAfter(now)) {
            subscriptionService.audit(tenantId, AUDIT_PAYMENT_SUSPENSION_ERASED, "unpaid 30 days after suspension");
            tenantLifecycle.eraseNow(tenantId);
            log.warn("Erased tenant {} — unpaid 30 days after payment suspension", tenantId);
        }
    }
}
