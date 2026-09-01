package com.aireceptionist.tenant.application;

import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import com.aireceptionist.tenant.port.in.TenantRetentionUseCase;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Story 5.2 (AC3, NFR28). Moved here (rather than living directly in
 * admin.service.ScheduledDataRetentionJob, which just triggers this on a cron schedule) so the
 * loop over {@link BusinessTenant} instances never crosses the tenant/admin module boundary —
 * see deferred W82 and this class's port, {@link TenantRetentionUseCase}.
 */
@Service
public class TenantRetentionService implements TenantRetentionUseCase {

    private static final Logger log = LoggerFactory.getLogger(TenantRetentionService.class);

    private final TenantRegistrationRepository tenantRepository;
    private final TenantDataRightsUseCase tenantDataRightsUseCase;

    public TenantRetentionService(TenantRegistrationRepository tenantRepository,
                                   TenantDataRightsUseCase tenantDataRightsUseCase) {
        this.tenantRepository = tenantRepository;
        this.tenantDataRightsUseCase = tenantDataRightsUseCase;
    }

    @Override
    public void eraseDueTerminatedTenants(Instant asOf) {
        List<BusinessTenant> due = tenantRepository.findAllByStatus(TenantStatus.TERMINATED).stream()
                .filter(tenant -> tenant.getTerminationScheduledAt() != null
                        && !tenant.getTerminationScheduledAt().isAfter(asOf))
                .toList();

        log.info("Scheduled data retention job: {} terminated tenant(s) past their 30-day retention window", due.size());
        for (BusinessTenant tenant : due) {
            try {
                tenantDataRightsUseCase.eraseTenantData(tenant.getId());
                tenant.markErased();
                tenantRepository.save(tenant);
                log.info("Erased and marked ERASED terminated tenant {}", tenant.getId());
            } catch (Exception ex) {
                log.error("Failed to erase terminated tenant {}", tenant.getId(), ex);
            }
        }
    }
}
