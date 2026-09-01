package com.aireceptionist.tenant.port.in;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 5.2 (AC1, AC6): lets other modules (whatsapp, voice) check a tenant's current status
 * without depending on the tenant module's internal domain types — returns the status name as
 * a String rather than exposing {@code tenant.domain.TenantStatus}.
 */
public interface GetTenantStatusUseCase {

    Optional<String> getStatus(UUID tenantId);
}
