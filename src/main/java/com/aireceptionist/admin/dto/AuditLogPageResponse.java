package com.aireceptionist.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 5.6 (AC1). Keyset/seek-paginated result — not {@link org.springframework.data.domain.Page},
 * since this endpoint writes an {@code ADMIN_AUDIT_VIEW} row into the same {@code occurred_at}-ordered
 * table it queries; {@code OFFSET}-based paging would drift by one row per prior page fetch (code
 * review, 2026-09-03). {@code nextCursorOccurredAt}/{@code nextCursorId} are the last row's
 * {@code (occurred_at, id)} — pass both back as {@code cursorOccurredAt}/{@code cursorId} to fetch
 * the next page. Both are {@code null} when {@code hasMore} is {@code false}.
 */
public record AuditLogPageResponse(
        List<AuditLogEntryResponse> content,
        boolean hasMore,
        Instant nextCursorOccurredAt,
        UUID nextCursorId
) {
}
