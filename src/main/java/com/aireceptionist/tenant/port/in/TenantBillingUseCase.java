package com.aireceptionist.tenant.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Epic 7: the billing module's view of tenant state. Exposes only primitives (no tenant domain
 * types) so billing never depends on tenant internals. {@code tenants.tier} is what the JWT
 * {@code tier} claim and voice gating read, so billing must keep it in step with the subscription.
 */
public interface TenantBillingUseCase {

    /** Story 7.1 (AC4). */
    void changeTier(UUID tenantId, String tier);

    /** Story 7.4 (AC1): LIVE → PAYMENT_SUSPENDED. */
    void suspendForPayment(UUID tenantId);

    /** Story 7.4 (AC3): PAYMENT_SUSPENDED → LIVE; no-op (false) for any other status, e.g. an admin suspension. */
    boolean restoreFromPaymentSuspension(UUID tenantId);

    /** Tenant ids in a given status (tenants has no RLS, so billing jobs enumerate tenants here). */
    List<UUID> getTenantIdsWithStatus(String status);
}
