package com.aireceptionist.common.audit;

import com.aireceptionist.common.multitenancy.TenantContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class AuditLogRepository implements AuditLogWriter {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AuditLogEntry entry) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try {
                try (PreparedStatement tenantStatement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
                    tenantStatement.setString(1, "app.current_tenant");
                    tenantStatement.setString(2, entry.tenantId().toString());
                    tenantStatement.execute();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO audit_log(id, tenant_id, event_type, confidence, message_hash, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, entry.id());
                    statement.setObject(2, entry.tenantId());
                    statement.setString(3, entry.eventType());
                    statement.setBigDecimal(4, entry.confidence());
                    statement.setString(5, entry.messageHash());
                    statement.setTimestamp(6, Timestamp.from(entry.occurredAt()));
                    statement.executeUpdate();
                }
            } finally {
                try (Statement tenantStatement = connection.createStatement()) {
                    tenantStatement.execute("RESET app.current_tenant");
                }
            }
            return null;
        });
    }

    public void markResolved(UUID auditLogId, Instant resolvedAt) {
        String tenantId = TenantContext.getCurrentTenant();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try {
                if (tenantId != null && !tenantId.isBlank()) {
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
                        stmt.setString(1, "app.current_tenant");
                        stmt.setString(2, tenantId);
                        stmt.execute();
                    }
                }
                try (PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE audit_log SET resolved = TRUE, resolved_at = ? WHERE id = ?")) {
                    stmt.setTimestamp(1, Timestamp.from(resolvedAt));
                    stmt.setObject(2, auditLogId);
                    stmt.executeUpdate();
                }
            } finally {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("RESET app.current_tenant");
                }
            }
            return null;
        });
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM audit_log WHERE occurred_at < ?", Timestamp.from(cutoff));
    }

    public long countByTenantIdAndEventTypeAndOccurredAtAfter(UUID tenantId, String eventType, Instant after) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try {
                try (PreparedStatement tenantStatement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
                    tenantStatement.setString(1, "app.current_tenant");
                    tenantStatement.setString(2, tenantId.toString());
                    tenantStatement.execute();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE tenant_id = ? AND event_type = ? AND occurred_at > ?
                        """)) {
                    statement.setObject(1, tenantId);
                    statement.setString(2, eventType);
                    statement.setTimestamp(3, Timestamp.from(after));
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getLong(1);
                    }
                }
            } finally {
                try (Statement tenantStatement = connection.createStatement()) {
                    tenantStatement.execute("RESET app.current_tenant");
                }
            }
        });
    }
}
