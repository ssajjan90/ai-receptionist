package com.aireceptionist.admin.service;

import com.aireceptionist.admin.dto.AdminDashboardResponse;
import com.aireceptionist.admin.dto.AuditEventSummary;
import com.aireceptionist.admin.dto.AuditLogEntryResponse;
import com.aireceptionist.admin.dto.AuditLogPageResponse;
import com.aireceptionist.admin.dto.BroadcastResult;
import com.aireceptionist.admin.dto.ConversationLogResponse;
import com.aireceptionist.admin.dto.TenantDetailResponse;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.common.util.Sha256;
import com.aireceptionist.tenant.port.in.TenantDataExport;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import com.aireceptionist.tenant.port.in.TenantLifecycleUseCase;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin queries read across tenants. Rather than bypassing row-level security via a
 * privileged database role (unverifiable in this environment and not guaranteed portable
 * across deployments — see Dev Agent Record), each tenant-scoped read sets
 * {@code app.current_tenant} to the specific tenant being read, matching the existing
 * per-tenant pattern in JdbcTenantDataStoreAdapter. `tenants` itself carries no RLS policy
 * (V1), so the base tenant list/lookup needs no tenant context at all.
 *
 * <p><b>Correction (story 5.3 code review, 2026-09-01):</b> the "unverifiable" concern above
 * turned out to matter more than assumed — RLS never applies to a Postgres superuser regardless
 * of {@code FORCE ROW LEVEL SECURITY}, and this app's dev/test connection IS the {@code postgres}
 * superuser, so {@code app.current_tenant}/RLS is silently a no-op here. The <em>actual</em>
 * tenant isolation in every query in this class comes from its own explicit {@code tenant_id = ?}
 * filter, with RLS as a second layer that only does something if/where the connecting role isn't
 * a superuser. See deferred W99 — whether that holds in production is unverified.</p>
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final String SUSPENSION_MESSAGE =
            "⚠️ Your CallSahayak service has been temporarily suspended. Please contact support.";
    private static final String REACTIVATION_MESSAGE =
            "✅ Your CallSahayak service has been reactivated. Welcome back!";
    private static final String TERMINATION_MESSAGE =
            "Your CallSahayak account has been terminated. Data will be retained for 30 days then permanently deleted.";

    private final JdbcTemplate jdbcTemplate;
    private final TenantLifecycleUseCase tenantLifecycleUseCase;
    private final TenantDataRightsUseCase tenantDataRightsUseCase;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;
    private final AuditLogRepository auditLogRepository;
    private final WhatsAppNotificationService notificationService;

    public AdminService(JdbcTemplate jdbcTemplate,
                        TenantLifecycleUseCase tenantLifecycleUseCase,
                        TenantDataRightsUseCase tenantDataRightsUseCase,
                        TenantOwnerPhonePort tenantOwnerPhonePort,
                        AuditLogRepository auditLogRepository,
                        WhatsAppNotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantLifecycleUseCase = tenantLifecycleUseCase;
        this.tenantDataRightsUseCase = tenantDataRightsUseCase;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
    }

    /**
     * Story 5.2 (AC1, AC4, AC5). {@code @Transactional} (code review, 2026-09-01) ties the status
     * change and the audit write into one atomic unit — safe now that {@link #notifyOwner} can no
     * longer throw and abort the transaction on a delivery failure.
     */
    @Transactional
    public void suspendTenant(UUID adminId, UUID tenantId) {
        tenantLifecycleUseCase.suspend(tenantId);
        recordAdminAction(adminId, tenantId, "ADMIN_SUSPEND");
        notifyOwner(tenantId, SUSPENSION_MESSAGE);
    }

    /** Story 5.2 (AC2, AC4, AC5). See {@link #suspendTenant} for the {@code @Transactional} rationale. */
    @Transactional
    public void reactivateTenant(UUID adminId, UUID tenantId) {
        tenantLifecycleUseCase.reactivate(tenantId);
        recordAdminAction(adminId, tenantId, "ADMIN_REACTIVATE");
        notifyOwner(tenantId, REACTIVATION_MESSAGE);
    }

    /**
     * Story 5.2 (AC3, AC4, AC5, NFR28) — schedules data erasure; TenantRetentionService does the
     * actual erasure. See {@link #suspendTenant} for the {@code @Transactional} rationale.
     */
    @Transactional
    public void terminateTenant(UUID adminId, UUID tenantId) {
        tenantLifecycleUseCase.terminate(tenantId);
        recordAdminAction(adminId, tenantId, "ADMIN_TERMINATE");
        notifyOwner(tenantId, TERMINATION_MESSAGE);
    }

    /**
     * Story 5.4 (AC1, AC2). No {@code @Transactional} — deliberately, so the external WhatsApp
     * send doesn't hold a DB connection open for the round trip (a risk flagged and accepted for
     * a different reason in story 5.2's review; avoided here since there's no state change to tie
     * atomically with the audit write this time).
     *
     * <p>{@code adminId} is unused in this method's body — kept because it matches this story's
     * own Task 1 signature exactly, and because the acting admin still needs threading through
     * for {@code broadcast}'s own audit trail via the generic {@code admin_access_log} (story
     * 5.1), even though this method's own audit row (see {@link #recordNotificationAudit}) does
     * not carry it (code review, 2026-09-01).</p>
     */
    public void notifyTenant(UUID adminId, UUID tenantId, String message) {
        requireTenantExists(tenantId);
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())
                .orElseThrow(() -> new NotFoundException("OWNER_PHONE_NOT_FOUND",
                        "Tenant has no owner phone on file: " + tenantId));
        notificationService.sendMessage(tenantId.toString(), ownerPhone, message);
        recordNotificationAudit(tenantId, message);
    }

    /**
     * Story 5.4 (AC4) — per-tenant failures (unknown tenant, no owner phone, delivery failure)
     * don't abort the batch. {@code tenantId} is defensively null-checked (code review,
     * 2026-09-01): {@code BroadcastRequest.tenantIds} now rejects null elements at the API
     * boundary, but a null here would otherwise NPE inside this very catch block
     * ({@code tenantId.toString()}), losing every result already collected.
     */
    public BroadcastResult broadcast(UUID adminId, List<UUID> tenantIds, String message) {
        int sent = 0;
        List<String> failedTenantIds = new ArrayList<>();
        for (UUID tenantId : tenantIds) {
            try {
                notifyTenant(adminId, tenantId, message);
                sent++;
            } catch (Exception ex) {
                log.warn("Broadcast notification failed for tenant={}: {}", tenantId, ex.getMessage());
                failedTenantIds.add(tenantId == null ? "null" : tenantId.toString());
            }
        }
        return new BroadcastResult(sent, failedTenantIds.size(), failedTenantIds);
    }

    /**
     * AC2: {@code messageHash} carries the first-50-chars preview (VARCHAR(64), per this story's
     * own Dev Notes) — unlike stories 5.2/5.3's convention of storing the acting admin's id there,
     * AC2 explicitly asks for the message preview instead. The acting admin is still captured, via
     * the generic per-endpoint {@code admin_access_log} (story 5.1), just not duplicated here.
     */
    private void recordNotificationAudit(UUID tenantId, String message) {
        String messagePreview = truncateToCodePoints(message, 50);
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "ADMIN_NOTIFICATION_SENT", null, messagePreview, Instant.now()));
    }

    /**
     * Code review, 2026-09-01: plain {@code String.substring(0, limit)} can split a UTF-16
     * surrogate pair — this codebase's own hardcoded admin messages use emoji ({@code SUSPENSION_MESSAGE}
     * etc.), so a message ending mid-emoji at the boundary isn't hypothetical. Cuts on a code
     * point boundary instead.
     */
    private static String truncateToCodePoints(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int cutIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, cutIndex);
    }

    /**
     * Story 5.5 (AC2) — same underlying export {@code TenantController}'s owner-facing endpoint
     * uses; adds an admin-specific {@code ADMIN_DATA_EXPORT} audit entry on top of the
     * {@code TENANT_DATA_EXPORTED} one {@code TenantDataRightsUseCase.exportTenantData} already
     * writes internally (see {@code AuditLogTenantAuditAdapter}) — same two-tier pattern as
     * {@link #findConversations}'s {@code ADMIN_CONVERSATION_VIEW}.
     */
    public TenantDataExport exportTenantData(UUID adminId, UUID tenantId) {
        requireTenantExists(tenantId);
        TenantDataExport export = tenantDataRightsUseCase.exportTenantData(tenantId);
        recordAdminAction(adminId, tenantId, "ADMIN_DATA_EXPORT");
        return export;
    }

    /**
     * Story 5.3 (AC1-AC5). Read-only — no write endpoints exist for conversation logs (Dev Notes).
     * PII redaction (AC5) matches on {@code Lead.phoneHash}, not the raw phone: {@code Lead.erase()}
     * nulls {@code phone} by design (see its javadoc), so the raw phone can never be used to find
     * an already-erased lead's messages after the fact — {@code phoneHash} is the one thing erasure
     * deliberately preserves for exactly this purpose.
     */
    public Page<ConversationLogResponse> findConversations(UUID adminId, UUID tenantId, Instant from, Instant to,
                                                            Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("INVALID_DATE_RANGE", "'from' must not be after 'to'.");
        }
        requireTenantExists(tenantId);

        Page<ConversationLogResponse> page = withTenantContext(tenantId,
                connection -> queryConversations(connection, tenantId, from, to, pageable));
        recordAdminAction(adminId, tenantId, "ADMIN_CONVERSATION_VIEW");
        return page;
    }

    /**
     * Story 5.6 (AC1-AC4). {@code tenantId} is required (unlike {@code from}/{@code to}/
     * {@code eventType}, all optional) since this reuses the same per-tenant {@code
     * app.current_tenant} pattern as {@link #findConversations} rather than a privileged
     * BYPASSRLS database role (see class javadoc) — the story's Dev Notes floated BYPASSRLS, but
     * that would contradict this class's already-established, already-reviewed approach for
     * every other cross-tenant admin read.
     *
     * <p>Code review, 2026-09-03: originally used {@code Page}/{@code OFFSET}, matching
     * {@link #findConversations}'s shape — but unlike that method, this endpoint's own
     * {@code recordAdminAction} write lands in the exact table it just queried, so every fetch
     * shifts a subsequent {@code OFFSET} page by one row. Switched to keyset/seek pagination on
     * {@code (occurred_at, id)}, which is immune to that drift since it never reuses a numeric
     * offset. This also drops the separate {@code COUNT(*)} query (no more total-vs-content
     * mismatch under concurrent writes — see the now-resolved count/select race note).
     */
    public AuditLogPageResponse queryAuditLog(UUID adminId, UUID tenantId, Instant from, Instant to, String eventType,
                                               Instant cursorOccurredAt, UUID cursorId, int pageSize) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("INVALID_DATE_RANGE", "'from' must not be after 'to'.");
        }
        if ((cursorOccurredAt == null) != (cursorId == null)) {
            throw new ValidationException("INVALID_CURSOR", "'cursorOccurredAt' and 'cursorId' must be supplied together.");
        }
        requireTenantExists(tenantId);

        AuditLogPageResponse page = withTenantContext(tenantId,
                connection -> selectAuditLogPage(connection, tenantId, from, to, eventType, cursorOccurredAt, cursorId, pageSize));
        recordAdminAction(adminId, tenantId, "ADMIN_AUDIT_VIEW");
        return page;
    }

    /** {@code tenants} carries no RLS policy (V1, see class javadoc) — no tenant context needed here. */
    private void requireTenantExists(UUID tenantId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Long.class, tenantId);
        if (count == null || count == 0) {
            throw new NotFoundException("TENANT_NOT_FOUND", "Tenant not found: " + tenantId);
        }
    }

    private Page<ConversationLogResponse> queryConversations(Connection connection, UUID tenantId, Instant from,
                                                              Instant to, Pageable pageable) throws SQLException {
        long total = countConversations(connection, tenantId, from, to);
        List<MessageRow> rows = selectConversations(connection, tenantId, from, to, pageable);
        Set<String> erasedPhoneHashes = findErasedPhoneHashes(connection, tenantId, rows);

        List<ConversationLogResponse> content = rows.stream()
                .map(row -> new ConversationLogResponse(
                        row.messageId(),
                        row.direction(),
                        isRedacted(row, erasedPhoneHashes) ? null : row.content(),
                        row.confidenceScore(),
                        row.language(),
                        row.receivedAt()))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // Guards against a null sender_phone (code review, 2026-09-01) — the column is NOT NULL at
    // the DB level (V5) so this can't currently happen, but Sha256.hex(null) would NPE if it ever
    // did, and findErasedPhoneHashes below already guards the same field defensively.
    private boolean isRedacted(MessageRow row, Set<String> erasedPhoneHashes) {
        return row.senderPhone() != null && erasedPhoneHashes.contains(Sha256.hex(row.senderPhone()));
    }

    private long countConversations(Connection connection, UUID tenantId, Instant from, Instant to) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM whatsapp_messages WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        appendDateRangeFilter(sql, params, from, to);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private List<MessageRow> selectConversations(Connection connection, UUID tenantId, Instant from, Instant to,
                                                 Pageable pageable) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT message_id, direction, sender_phone, content, confidence_score, language, received_at
                FROM whatsapp_messages WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        appendDateRangeFilter(sql, params, from, to);
        sql.append(" ORDER BY received_at DESC LIMIT ? OFFSET ?");
        params.add(pageable.getPageSize());
        params.add(pageable.getOffset());

        List<MessageRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new MessageRow(
                            resultSet.getString("message_id"),
                            resultSet.getString("direction"),
                            resultSet.getString("sender_phone"),
                            resultSet.getString("content"),
                            resultSet.getBigDecimal("confidence_score"),
                            resultSet.getString("language"),
                            resultSet.getTimestamp("received_at").toInstant()));
                }
            }
        }
        return rows;
    }

    private void appendDateRangeFilter(StringBuilder sql, List<Object> params, Instant from, Instant to) {
        if (from != null) {
            sql.append(" AND received_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND received_at <= ?");
            params.add(Timestamp.from(to));
        }
    }

    private void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    /**
     * Story 5.6 (AC1-AC3), keyset-paginated (code review, 2026-09-03 — see {@link #queryAuditLog}).
     * Differs from {@link #appendDateRangeFilter} in two ways: the column is {@code occurred_at}
     * (not {@code received_at}), and AC3's 90-day retention window is enforced unconditionally
     * here — not an optional filter, so it's baked into the base {@code WHERE} clause rather than
     * appended alongside {@code from}/{@code to}. Fetches one extra row beyond {@code pageSize} to
     * determine {@code hasMore} without a separate {@code COUNT(*)} query.
     */
    private AuditLogPageResponse selectAuditLogPage(Connection connection, UUID tenantId, Instant from, Instant to,
                                                      String eventType, Instant cursorOccurredAt, UUID cursorId,
                                                      int pageSize) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, event_type, confidence, message_hash, occurred_at
                FROM audit_log WHERE tenant_id = ? AND occurred_at > NOW() - INTERVAL '90 days'
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        appendAuditLogFilters(sql, params, from, to, eventType);
        if (cursorOccurredAt != null) {
            sql.append(" AND (occurred_at, id) < (?, ?)");
            params.add(Timestamp.from(cursorOccurredAt));
            params.add(cursorId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        params.add(pageSize + 1);

        List<AuditLogEntryResponse> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new AuditLogEntryResponse(
                            (UUID) resultSet.getObject("id"),
                            (UUID) resultSet.getObject("tenant_id"),
                            resultSet.getString("event_type"),
                            resultSet.getBigDecimal("confidence"),
                            resultSet.getString("message_hash"),
                            resultSet.getTimestamp("occurred_at").toInstant()));
                }
            }
        }

        boolean hasMore = rows.size() > pageSize;
        List<AuditLogEntryResponse> content = hasMore ? rows.subList(0, pageSize) : rows;
        AuditLogEntryResponse last = content.isEmpty() ? null : content.get(content.size() - 1);
        return new AuditLogPageResponse(
                content,
                hasMore,
                hasMore ? last.occurredAt() : null,
                hasMore ? last.id() : null);
    }

    private void appendAuditLogFilters(StringBuilder sql, List<Object> params, Instant from, Instant to, String eventType) {
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at <= ?");
            params.add(Timestamp.from(to));
        }
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
    }

    /**
     * {@code leads} carries the same {@code app.current_tenant} RLS policy as
     * {@code whatsapp_messages}, but RLS never applies to the Postgres superuser role this app
     * connects as in dev/test — explicit {@code tenant_id = ?} is the real scoping mechanism here,
     * matching every other query in this class (found via a cross-tenant leak in this method's own
     * tests, code review discipline applied proactively during initial implementation).
     */
    private Set<String> findErasedPhoneHashes(Connection connection, UUID tenantId, List<MessageRow> rows)
            throws SQLException {
        Set<String> candidateHashes = rows.stream()
                .map(MessageRow::senderPhone)
                .filter(java.util.Objects::nonNull)
                .map(Sha256::hex)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (candidateHashes.isEmpty()) {
            return Set.of();
        }

        Set<String> erasedHashes = new HashSet<>();
        Array hashArray = connection.createArrayOf("varchar", candidateHashes.toArray());
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT phone_hash FROM leads WHERE tenant_id = ? AND erased = TRUE AND phone_hash = ANY(?)")) {
            statement.setObject(1, tenantId);
            statement.setArray(2, hashArray);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    erasedHashes.add(resultSet.getString("phone_hash"));
                }
            }
        }
        return erasedHashes;
    }

    private record MessageRow(String messageId, String direction, String senderPhone, String content,
                              BigDecimal confidenceScore, String language, Instant receivedAt) {
    }

    /**
     * AC4 asks for this written to {@code audit_log} with the acting admin's userId — that table
     * has no admin-id column, so (matching the established messageHash-repurposing convention
     * from story 4.3's bulk erasure count) the admin id is carried in {@code messageHash}. Unlike
     * story 5.1's AC5 (a cross-tenant listing with no single target), these actions genuinely are
     * single-tenant, so audit_log's tenant-scoped design fits without deviation.
     */
    private void recordAdminAction(UUID adminId, UUID tenantId, String eventType) {
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, eventType, null, adminId.toString(), Instant.now()));
    }

    /**
     * Delivery failure here must not fail (or, now that this runs inside {@code @Transactional},
     * roll back) an already-decided admin action — matches the try/catch pattern this same story
     * already uses for the customer-facing suspended-tenant reply in {@code WhatsAppMessageService}
     * (code review, 2026-09-01).
     */
    private void notifyOwner(UUID tenantId, String message) {
        tenantOwnerPhonePort.getOwnerPhone(tenantId.toString()).ifPresent(ownerPhone -> {
            try {
                notificationService.sendMessage(tenantId.toString(), ownerPhone, message);
            } catch (Exception ex) {
                log.warn("Failed to deliver owner notification for tenant={}: {}", tenantId, ex.getMessage());
            }
        });
    }

    public Page<AdminDashboardResponse> findAllTenants(Pageable pageable) {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants", Long.class);
        // ORDER BY created_at, id: this endpoint doesn't implement client-requested sort (any
        // `sort` param on the request is not honored, see below) — created_at is always the
        // actual order, with id as a tiebreaker for deterministic pagination when timestamps
        // collide (see code review of story 5-1, 2026-09-01).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, business_name, tier, status
                FROM tenants
                ORDER BY created_at, id
                LIMIT ? OFFSET ?
                """, pageable.getPageSize(), pageable.getOffset());

        List<AdminDashboardResponse> content = rows.stream()
                .map(row -> {
                    UUID tenantId = (UUID) row.get("id");
                    TenantActivity activity = loadTenantActivity(tenantId);
                    return new AdminDashboardResponse(
                            tenantId,
                            (String) row.get("business_name"),
                            (String) row.get("tier"),
                            (String) row.get("status"),
                            activity.lastActiveAt(),
                            activity.conversationCountLast24h(),
                            activity.subscriptionStatus());
                })
                .toList();

        // Report the Pageable that reflects what was actually executed, not the client's raw
        // request — the query above doesn't honor client-requested sort, so echoing that sort
        // back in the Page metadata would misrepresent the real ordering.
        Pageable actualPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        return new PageImpl<>(content, actualPageable, total == null ? 0 : total);
    }

    public TenantDetailResponse findTenantDetail(UUID tenantId) {
        Map<String, Object> tenantRow;
        try {
            tenantRow = jdbcTemplate.queryForMap(
                    "SELECT id, business_name, tier, status FROM tenants WHERE id = ?", tenantId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new NotFoundException("TENANT_NOT_FOUND", "Tenant not found: " + tenantId);
        }

        TenantActivity activity = loadTenantActivity(tenantId);
        TenantDetail detail = loadTenantDetail(tenantId);

        return new TenantDetailResponse(
                tenantId,
                (String) tenantRow.get("business_name"),
                (String) tenantRow.get("tier"),
                (String) tenantRow.get("status"),
                activity.lastActiveAt(),
                activity.conversationCountLast24h(),
                activity.subscriptionStatus(),
                detail.kbEntryCount(),
                detail.leadCount(),
                detail.recentAuditEvents());
    }

    private TenantActivity loadTenantActivity(UUID tenantId) {
        return withTenantContext(tenantId, connection -> {
            // Merged into one query (was two) to cut per-tenant round trips inside the loop in
            // findAllTenants — this doesn't eliminate the per-tenant SET/RESET cost itself (see
            // deferred W85), just the query count within each tenant's context window.
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        (SELECT status FROM subscriptions WHERE tenant_id = ?) AS subscription_status,
                        (SELECT MAX(received_at) FROM whatsapp_messages WHERE tenant_id = ?) AS last_active,
                        (SELECT COUNT(*) FROM whatsapp_messages WHERE tenant_id = ? AND received_at > ?) AS last_24h
                    """)) {
                statement.setObject(1, tenantId);
                statement.setObject(2, tenantId);
                statement.setObject(3, tenantId);
                statement.setTimestamp(4, Timestamp.from(Instant.now().minusSeconds(86_400)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    String subscriptionStatus = resultSet.getString("subscription_status");
                    Timestamp lastActive = resultSet.getTimestamp("last_active");
                    Instant lastActiveAt = lastActive == null ? null : lastActive.toInstant();
                    long conversationCountLast24h = resultSet.getLong("last_24h");
                    return new TenantActivity(lastActiveAt, conversationCountLast24h, subscriptionStatus);
                }
            }
        });
    }

    private TenantDetail loadTenantDetail(UUID tenantId) {
        return withTenantContext(tenantId, connection -> {
            long kbEntryCount = countRows(connection, "knowledge_entries", tenantId);
            long leadCount = countRows(connection, "leads", tenantId);

            List<AuditEventSummary> recentAuditEvents = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, event_type, confidence, occurred_at
                    FROM audit_log
                    WHERE tenant_id = ?
                    ORDER BY occurred_at DESC
                    LIMIT 10
                    """)) {
                statement.setObject(1, tenantId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        recentAuditEvents.add(new AuditEventSummary(
                                (UUID) resultSet.getObject("id"),
                                resultSet.getString("event_type"),
                                resultSet.getBigDecimal("confidence"),
                                resultSet.getTimestamp("occurred_at").toInstant()));
                    }
                }
            }

            return new TenantDetail(kbEntryCount, leadCount, recentAuditEvents);
        });
    }

    private long countRows(Connection connection, String table, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?")) {
            statement.setObject(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private <T> T withTenantContext(UUID tenantId, TenantWork<T> work) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            try {
                setTenant(connection, tenantId);
                return work.execute(connection);
            } finally {
                resetTenant(connection);
            }
        });
    }

    private void setTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private void resetTenant(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET app.current_tenant");
        }
    }

    private record TenantActivity(Instant lastActiveAt, long conversationCountLast24h, String subscriptionStatus) {
    }

    private record TenantDetail(long kbEntryCount, long leadCount, List<AuditEventSummary> recentAuditEvents) {
    }

    @FunctionalInterface
    private interface TenantWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
