package com.aireceptionist.tenant.port.in;

import java.time.Instant;

/**
 * Story 5.2 (AC3, NFR28): erases any TERMINATED tenant whose 30-day retention window has elapsed
 * as of {@code asOf}. Lives in the tenant module (not admin) so the erasure loop over
 * {@code BusinessTenant} instances never needs to cross a module boundary — see
 * admin.service.ScheduledDataRetentionJob, which just triggers this on a cron schedule.
 */
public interface TenantRetentionUseCase {

    void eraseDueTerminatedTenants(Instant asOf);
}
