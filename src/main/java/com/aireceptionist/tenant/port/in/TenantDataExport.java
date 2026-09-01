package com.aireceptionist.tenant.port.in;

// Story 5.5 code review: moved from tenant.domain (deliberately unexposed, alongside
// BusinessTenant) to tenant.port.in (already an exposed NamedInterface, already home to other
// plain result records like ConnectedTenantWhatsApp) — admin.service.AdminService needs to
// return this type directly from its own export endpoint (AC2), which tenant.domain doesn't allow.

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TenantDataExport(
        UUID tenantId,
        Instant exportedAt,
        List<Map<String, Object>> knowledgeEntries,
        List<Map<String, Object>> leads,
        List<String> messageHashes
) {
}
