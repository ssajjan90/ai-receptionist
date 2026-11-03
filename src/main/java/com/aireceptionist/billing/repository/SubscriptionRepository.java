package com.aireceptionist.billing.repository;

import com.aireceptionist.billing.domain.Subscription;
import com.aireceptionist.billing.domain.SubscriptionStatus;
import com.aireceptionist.billing.domain.SubscriptionTier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 7.1 (Task 2) {@code subscriptions} access. JDBC rather than a JPA repository (deviation
 * from the story): {@code subscriptions} has FORCE ROW LEVEL SECURITY, and billing touches it from
 * three contexts where the JPA session's tenant is unreliable — the Razorpay webhook (no JWT; the
 * open-in-view EntityManager is bound to the DEFAULT tenant before the controller runs, the issue
 * story 6.2 hit), and the hourly/5-minute schedulers, which iterate many tenants on one thread.
 * Every call here sets {@code app.current_tenant} itself on its connection and restores the
 * previous value afterwards (restore, not RESET, so it never clobbers an enclosing transaction's
 * tenant) — the same approach as tenant's {@code JdbcSubscriptionProvisioningAdapter}.
 */
@Repository
public class SubscriptionRepository {

    private static final String COLUMNS = "id, tenant_id, tier, status, conversation_used_this_month, "
            + "conversation_limit, next_billing_date, grace_period_ends_at, suspended_at, "
            + "razorpay_subscription_id, razorpay_plan_id, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Subscription> findByTenantId(UUID tenantId) {
        return inTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM subscriptions WHERE tenant_id = ?")) {
                statement.setObject(1, tenantId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Subscription>empty();
                }
            }
        });
    }

    /** Upsert on the one-row-per-tenant constraint. */
    public void save(Subscription s) {
        inTenant(s.getTenantId(), connection -> {
            try (PreparedStatement st = connection.prepareStatement("""
                    INSERT INTO subscriptions (id, tenant_id, tier, status, conversation_used_this_month,
                        conversation_limit, next_billing_date, grace_period_ends_at, suspended_at,
                        razorpay_subscription_id, razorpay_plan_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id) DO UPDATE SET
                        tier = EXCLUDED.tier, status = EXCLUDED.status,
                        conversation_used_this_month = EXCLUDED.conversation_used_this_month,
                        conversation_limit = EXCLUDED.conversation_limit,
                        next_billing_date = EXCLUDED.next_billing_date,
                        grace_period_ends_at = EXCLUDED.grace_period_ends_at,
                        suspended_at = EXCLUDED.suspended_at,
                        razorpay_subscription_id = EXCLUDED.razorpay_subscription_id,
                        razorpay_plan_id = EXCLUDED.razorpay_plan_id,
                        updated_at = EXCLUDED.updated_at
                    """)) {
                st.setObject(1, s.getId());
                st.setObject(2, s.getTenantId());
                st.setString(3, s.getTier().name());
                st.setString(4, s.getStatus().name());
                st.setInt(5, s.getConversationUsedThisMonth());
                if (s.getConversationLimit() == null) {
                    st.setNull(6, Types.INTEGER);
                } else {
                    st.setInt(6, s.getConversationLimit());
                }
                st.setTimestamp(7, ts(s.getNextBillingDate()));
                st.setTimestamp(8, ts(s.getGracePeriodEndsAt()));
                st.setTimestamp(9, ts(s.getSuspendedAt()));
                st.setString(10, s.getRazorpaySubscriptionId());
                st.setString(11, s.getRazorpayPlanId());
                st.setTimestamp(12, ts(s.getCreatedAt()));
                st.setTimestamp(13, ts(s.getUpdatedAt()));
                st.executeUpdate();
            }
            return null;
        });
    }

    private <T> T inTenant(UUID tenantId, SqlWork<T> work) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            String previous = currentTenant(connection);
            setTenant(connection, tenantId.toString());
            try {
                return work.run(connection);
            } finally {
                if (previous == null || previous.isEmpty()) {
                    try (var st = connection.createStatement()) {
                        st.execute("RESET app.current_tenant");
                    }
                } else {
                    setTenant(connection, previous);
                }
            }
        });
    }

    private static String currentTenant(Connection connection) throws SQLException {
        try (var st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void setTenant(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            st.setString(1, tenantId);
            st.execute();
        }
    }

    private static Subscription map(ResultSet rs) throws SQLException {
        int limit = rs.getInt("conversation_limit");
        Integer conversationLimit = rs.wasNull() ? null : limit;
        return new Subscription(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                SubscriptionTier.parseOrBasic(rs.getString("tier")),
                SubscriptionStatus.valueOf(rs.getString("status")),
                rs.getInt("conversation_used_this_month"),
                conversationLimit,
                instant(rs.getTimestamp("next_billing_date")),
                instant(rs.getTimestamp("grace_period_ends_at")),
                instant(rs.getTimestamp("suspended_at")),
                rs.getString("razorpay_subscription_id"),
                rs.getString("razorpay_plan_id"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
