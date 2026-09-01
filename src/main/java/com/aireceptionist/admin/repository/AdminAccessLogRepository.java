package com.aireceptionist.admin.repository;

import com.aireceptionist.admin.domain.AdminAccessLogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class AdminAccessLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminAccessLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AdminAccessLogEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO admin_access_log(id, admin_user_id, target_tenant_id, event_type, action, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                entry.id(), entry.adminUserId(), entry.targetTenantId(), entry.eventType(),
                entry.action(), Timestamp.from(entry.occurredAt()));
    }
}
