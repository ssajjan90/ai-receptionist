package com.aireceptionist.tenant.application;

import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.2 (AC3, NFR28). Moved from admin.ScheduledRetentionJobTest when the erasure loop moved
 * from admin.service.ScheduledDataRetentionJob into this class, to fix a Spring Modulith boundary
 * violation (admin depending on tenant's internal domain package) — see deferred W82.
 */
class TenantRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    @Test
    void erasesOnlyTerminatedTenantsPastTheirThirtyDayRetentionWindow() {
        TenantRegistrationRepository tenantRepository = mock(TenantRegistrationRepository.class);
        TenantDataRightsUseCase tenantDataRightsUseCase = mock(TenantDataRightsUseCase.class);

        UUID dueId = UUID.randomUUID();
        BusinessTenant due = terminatedTenant(dueId, Instant.parse("2026-08-31T03:00:00Z")); // in the past — due
        UUID dueExactlyNowId = UUID.randomUUID();
        BusinessTenant dueExactlyNow = terminatedTenant(dueExactlyNowId, Instant.parse("2026-09-01T03:00:00Z")); // exactly now — due
        UUID notDueId = UUID.randomUUID();
        BusinessTenant notDue = terminatedTenant(notDueId, Instant.parse("2026-09-02T03:00:00Z")); // in the future — not due
        UUID noScheduleId = UUID.randomUUID();
        BusinessTenant noSchedule = terminatedTenant(noScheduleId, null); // TERMINATED but never scheduled

        when(tenantRepository.findAllByStatus(TenantStatus.TERMINATED))
                .thenReturn(List.of(due, dueExactlyNow, notDue, noSchedule));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new TenantRetentionService(tenantRepository, tenantDataRightsUseCase).eraseDueTerminatedTenants(NOW);

        verify(tenantDataRightsUseCase).eraseTenantData(dueId);
        verify(tenantDataRightsUseCase).eraseTenantData(dueExactlyNowId);
        verify(tenantDataRightsUseCase, never()).eraseTenantData(notDueId);
        verify(tenantDataRightsUseCase, never()).eraseTenantData(noScheduleId);

        assertThat(due.getStatus()).isEqualTo(TenantStatus.ERASED);
        assertThat(dueExactlyNow.getStatus()).isEqualTo(TenantStatus.ERASED);
        assertThat(notDue.getStatus()).isEqualTo(TenantStatus.TERMINATED);
        assertThat(noSchedule.getStatus()).isEqualTo(TenantStatus.TERMINATED);
    }

    @Test
    void oneTenantErasureFailureDoesNotStopTheOthers() {
        TenantRegistrationRepository tenantRepository = mock(TenantRegistrationRepository.class);
        TenantDataRightsUseCase tenantDataRightsUseCase = mock(TenantDataRightsUseCase.class);

        UUID failingId = UUID.randomUUID();
        BusinessTenant failing = terminatedTenant(failingId, Instant.parse("2026-08-01T03:00:00Z"));
        UUID healthyId = UUID.randomUUID();
        BusinessTenant healthy = terminatedTenant(healthyId, Instant.parse("2026-08-01T03:00:00Z"));

        when(tenantRepository.findAllByStatus(TenantStatus.TERMINATED)).thenReturn(List.of(failing, healthy));
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("DB blip"))
                .when(tenantDataRightsUseCase).eraseTenantData(failingId);

        new TenantRetentionService(tenantRepository, tenantDataRightsUseCase).eraseDueTerminatedTenants(NOW);

        verify(tenantDataRightsUseCase).eraseTenantData(healthyId);
        assertThat(healthy.getStatus()).isEqualTo(TenantStatus.ERASED);
        assertThat(failing.getStatus()).isEqualTo(TenantStatus.TERMINATED); // never got marked erased
    }

    private static BusinessTenant terminatedTenant(UUID id, Instant terminationScheduledAt) {
        Instant now = Instant.now();
        return BusinessTenant.restore(
                id, "Test Business", "Owner", "+919000000000", "+919000000001",
                "owner@example.com", "hash", TenantStatus.TERMINATED, "PRO", "en",
                null, null, null, null, null, now, now, now, now,
                terminationScheduledAt);
    }
}
