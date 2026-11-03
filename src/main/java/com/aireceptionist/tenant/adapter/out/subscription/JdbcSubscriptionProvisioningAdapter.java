package com.aireceptionist.tenant.adapter.out.subscription;

import com.aireceptionist.tenant.port.out.SubscriptionProvisioningPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.UUID;

/** Epic 7 code review: BASIC rows must carry the 300/month limit (PRD) — NULL means unlimited. */
@Component
public class JdbcSubscriptionProvisioningAdapter implements SubscriptionProvisioningPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubscriptionProvisioningAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void provisionBasicSubscription(UUID tenantId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try {
                try (Statement tenantStatement = connection.createStatement()) {
                    tenantStatement.execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO subscriptions (id, tenant_id, tier, status, conversation_limit, created_at)
                        VALUES (gen_random_uuid(), ?, 'BASIC', 'ACTIVE', 300, NOW())
                        """)) {
                    statement.setObject(1, tenantId);
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
}
