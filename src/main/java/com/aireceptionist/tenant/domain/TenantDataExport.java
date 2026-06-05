package com.aireceptionist.tenant.domain;

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
