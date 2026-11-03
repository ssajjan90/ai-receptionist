package com.aireceptionist.billing;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Shared seeding/inspection for Epic 7 integration tests (RLS-aware where the table needs it). */
final class BillingTestSupport {

    private BillingTestSupport() {
    }

    static UUID seedTenant(JdbcTemplate jdbc, String tier, String status) {
        UUID id = UUID.randomUUID();
        long n = System.nanoTime();
        jdbc.update("INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, ?, ?)",
                id, "Billing Test Shop", "+91" + (n % 10_000_000_000L), "+914" + String.format("%09d", n % 1_000_000_000L),
                tier, status);
        return id;
    }

    static String tenantColumn(JdbcTemplate jdbc, UUID tenantId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tenants WHERE id = ?", String.class, tenantId);
    }

    static int auditCount(JdbcTemplate jdbc, UUID tenantId, String eventType) {
        Integer count = jdbc.execute((ConnectionCallback<Integer>) c -> {
            try (var set = c.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (var q = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND event_type = ?")) {
                q.setObject(1, tenantId);
                q.setString(2, eventType);
                try (var rs = q.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                c.createStatement().execute("RESET app.current_tenant");
            }
        });
        return count == null ? 0 : count;
    }
}
