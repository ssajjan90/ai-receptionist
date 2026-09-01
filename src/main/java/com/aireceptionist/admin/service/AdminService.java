package com.aireceptionist.admin.service;

import com.aireceptionist.admin.dto.AdminDashboardResponse;
import com.aireceptionist.admin.dto.AuditEventSummary;
import com.aireceptionist.admin.dto.TenantDetailResponse;
import com.aireceptionist.common.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin queries read across tenants. Rather than bypassing row-level security via a
 * privileged database role (unverifiable in this environment and not guaranteed portable
 * across deployments — see Dev Agent Record), each tenant-scoped read sets
 * {@code app.current_tenant} to the specific tenant being read, matching the existing
 * per-tenant pattern in JdbcTenantDataStoreAdapter. `tenants` itself carries no RLS policy
 * (V1), so the base tenant list/lookup needs no tenant context at all.
 */
@Service
public class AdminService {

    private final JdbcTemplate jdbcTemplate;

    public AdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Page<AdminDashboardResponse> findAllTenants(Pageable pageable) {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants", Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, business_name, tier, status
                FROM tenants
                ORDER BY created_at
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

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
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
            String subscriptionStatus = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT status FROM subscriptions WHERE tenant_id = ?")) {
                statement.setObject(1, tenantId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        subscriptionStatus = resultSet.getString(1);
                    }
                }
            }

            Instant lastActiveAt = null;
            long conversationCountLast24h = 0;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT MAX(received_at) AS last_active,
                           COUNT(*) FILTER (WHERE received_at > ?) AS last_24h
                    FROM whatsapp_messages
                    WHERE tenant_id = ?
                    """)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(86_400)));
                statement.setObject(2, tenantId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        Timestamp lastActive = resultSet.getTimestamp("last_active");
                        lastActiveAt = lastActive == null ? null : lastActive.toInstant();
                        conversationCountLast24h = resultSet.getLong("last_24h");
                    }
                }
            }

            return new TenantActivity(lastActiveAt, conversationCountLast24h, subscriptionStatus);
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
