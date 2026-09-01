package com.aireceptionist.admin;

import com.aireceptionist.admin.service.ScheduledDataRetentionJob;
import com.aireceptionist.tenant.port.in.TenantRetentionUseCase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Story 5.2 (AC3, NFR28). The actual erasure logic now lives in
 * tenant.application.TenantRetentionService (see TenantRetentionServiceTest) — this class only
 * verifies the scheduled job delegates with the correct "as of" instant. Moved out of this job to
 * fix a Spring Modulith boundary violation (admin depending on tenant's internal domain package
 * via BusinessTenant/TenantStatus) — see deferred W82.
 */
class ScheduledRetentionJobTest {

    @Test
    void delegatesToTenantRetentionUseCaseWithTheCurrentInstant() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC);
        TenantRetentionUseCase tenantRetentionUseCase = mock(TenantRetentionUseCase.class);

        new ScheduledDataRetentionJob(tenantRetentionUseCase, fixedClock).run();

        verify(tenantRetentionUseCase).eraseDueTerminatedTenants(Instant.parse("2026-09-01T03:00:00Z"));
    }
}
