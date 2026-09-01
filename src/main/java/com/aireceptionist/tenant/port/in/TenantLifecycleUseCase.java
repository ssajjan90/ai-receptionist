package com.aireceptionist.tenant.port.in;

import java.util.UUID;

/**
 * Story 5.2 (AC1, AC2, AC3): tenant lifecycle transitions — {@code suspend}/{@code reactivate}/
 * {@code terminate} are admin-initiated; {@link #eraseNow} (story 5.5) is owner-initiated
 * (DPDP right-to-erasure, immediate — distinct from {@code terminate}'s 30-day grace period).
 * Deliberately exposes only {@code UUID} in/out — not {@link com.aireceptionist.tenant.domain.BusinessTenant}
 * — so cross-module callers (admin.service.AdminService) never depend on tenant's internal domain
 * package, which Spring Modulith correctly flags as a non-exposed type otherwise.
 */
public interface TenantLifecycleUseCase {

    void suspend(UUID tenantId);

    void reactivate(UUID tenantId);

    void terminate(UUID tenantId);

    void eraseNow(UUID tenantId);
}
