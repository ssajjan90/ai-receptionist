package com.aireceptionist.admin.service;

import com.aireceptionist.tenant.port.in.TenantRetentionUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Story 5.2 (AC3, NFR28): daily trigger for erasing TERMINATED tenants whose 30-day retention
 * window has elapsed. The actual work happens in tenant.application.TenantRetentionService — kept
 * out of this class so the admin module never depends on tenant's internal domain package (see
 * deferred W82; this split replaced an earlier version of this job that did the erasure loop
 * directly against BusinessTenant, which Spring Modulith correctly flagged as a boundary violation).
 */
@Component
public class ScheduledDataRetentionJob {

    private final TenantRetentionUseCase tenantRetentionUseCase;
    private final Clock clock;

    public ScheduledDataRetentionJob(TenantRetentionUseCase tenantRetentionUseCase, Clock clock) {
        this.tenantRetentionUseCase = tenantRetentionUseCase;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void run() {
        tenantRetentionUseCase.eraseDueTerminatedTenants(Instant.now(clock));
    }
}
