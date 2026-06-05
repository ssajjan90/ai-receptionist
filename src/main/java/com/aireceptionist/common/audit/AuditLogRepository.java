package com.aireceptionist.common.audit;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

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
                try (Statement tenantStatement = connection.createStatement()) {
                    tenantStatement.execute(
                            "SELECT set_config('app.current_tenant', '" + entry.tenantId() + "', false)");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO audit_log(tenant_id, event_type, confidence, message_hash, occurred_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, entry.tenantId());
                    statement.setString(2, entry.eventType());
                    statement.setBigDecimal(3, entry.confidence());
                    statement.setString(4, entry.messageHash());
                    statement.setTimestamp(5, Timestamp.from(entry.occurredAt()));
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

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM audit_log WHERE occurred_at < ?", Timestamp.from(cutoff));
    }
}
