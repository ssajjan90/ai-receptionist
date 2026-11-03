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
 * Story 7.1 (Task 2) {@code subscriptions} access over JDBC (deviation from the story):
 * {@code subscriptions} has FORCE ROW LEVEL SECURITY and billing touches it from contexts where
 * the JPA session's tenant is unreliable — the Razorpay webhook (no JWT; open-in-view binds the
 * DEFAULT tenant first, the issue story 6.2 hit) and schedulers iterating many tenants. Each call
 * sets {@code app.current_tenant} on its connection and restores the previous value afterwards.
 * Inside a Spring transaction the JdbcTemplate shares the transaction's connection, so billing
 * transitions can be atomic with the tenant-module update they pair with.
 *
 * <p>Code review: writes are <b>targeted, conditional UPDATEs</b> — never a whole-row save of a
 * snapshot — so the 5-minute usage sync or the hourly grace job can't write stale state over a
 * webhook's payment/tier change, and state transitions are compare-and-set (they return whether
 * they applied). {@link #save} remains only for seeding rows (tests).
 */
@Repository
public class SubscriptionRepository {

    private static final String COLUMNS = "id, tenant_id, tier, status, conversation_used_this_month, "
            + "conversation_limit, next_billing_date, grace_period_ends_at, suspended_at, "
            + "razorpay_subscription_id, razorpay_plan_id, pending_razorpay_subscription_id, pending_razorpay_plan_id, "
            + "pending_checkout_url, last_event_at, billing_anchor_at, created_at, updated_at";

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

    /** Creates the default BASIC row if the tenant has none (pre-Epic-7 tenants). */
    public void ensureExists(UUID tenantId, Instant now) {
        update(tenantId, """
                INSERT INTO subscriptions (id, tenant_id, tier, status, conversation_limit, billing_anchor_at, created_at, updated_at)
                VALUES (?, ?, 'BASIC', 'ACTIVE', ?, ?, ?, ?) ON CONFLICT (tenant_id) DO NOTHING
                """, UUID.randomUUID(), tenantId, SubscriptionTier.BASIC.conversationLimit(), ts(now), ts(now), ts(now));
    }

    public boolean updateTier(UUID tenantId, SubscriptionTier tier, Instant now) {
        return update(tenantId, "UPDATE subscriptions SET tier = ?, conversation_limit = ?, updated_at = ? WHERE tenant_id = ?",
                tier.name(), tier.conversationLimit(), ts(now), tenantId) > 0;
    }

    /** Story 7.2 (AC4): paid → ACTIVE; next billing date / anchor only move when Razorpay gives one. */
    public void markPaid(UUID tenantId, Instant nextBillingDate, Instant now) {
        update(tenantId, """
                UPDATE subscriptions SET status = 'ACTIVE', grace_period_ends_at = NULL, suspended_at = NULL,
                    next_billing_date = COALESCE(?, next_billing_date),
                    billing_anchor_at = COALESCE(?, billing_anchor_at), updated_at = ?
                WHERE tenant_id = ?""", ts(nextBillingDate), ts(nextBillingDate), ts(now), tenantId);
    }

    /** Story 7.2 (AC5): only from a good-standing state — never restarts a running grace clock. */
    public boolean startGrace(UUID tenantId, Instant graceEndsAt, Instant now) {
        return update(tenantId, """
                UPDATE subscriptions SET status = 'PAST_DUE', grace_period_ends_at = ?, updated_at = ?
                WHERE tenant_id = ? AND status NOT IN ('PAST_DUE', 'SUSPENDED')""", ts(graceEndsAt), ts(now), tenantId) > 0;
    }

    /** Story 7.4 (AC1): only if still PAST_DUE with an expired grace period (a payment may have landed). */
    public boolean suspendIfGraceExpired(UUID tenantId, Instant now) {
        return update(tenantId, """
                UPDATE subscriptions SET status = 'SUSPENDED', suspended_at = ?, updated_at = ?
                WHERE tenant_id = ? AND status = 'PAST_DUE' AND grace_period_ends_at <= ?""",
                ts(now), ts(now), tenantId, ts(now)) > 0;
    }

    /** Story 7.4 (AC3): PAST_DUE/SUSPENDED → ACTIVE. */
    public boolean restore(UUID tenantId, Instant now) {
        return update(tenantId, """
                UPDATE subscriptions SET status = 'ACTIVE', grace_period_ends_at = NULL, suspended_at = NULL, updated_at = ?
                WHERE tenant_id = ? AND status IN ('PAST_DUE', 'SUSPENDED')""", ts(now), tenantId) > 0;
    }

    /** Code-review decision: a cancelled plan drops to BASIC (data kept). */
    public void cancelToBasic(UUID tenantId, Instant now) {
        update(tenantId, """
                UPDATE subscriptions SET tier = 'BASIC', conversation_limit = ?, razorpay_subscription_id = NULL,
                    razorpay_plan_id = NULL,
                    status = CASE WHEN status = 'SUSPENDED' THEN status ELSE 'ACTIVE' END,
                    grace_period_ends_at = CASE WHEN status = 'SUSPENDED' THEN grace_period_ends_at ELSE NULL END,
                    updated_at = ?
                WHERE tenant_id = ?""", SubscriptionTier.BASIC.conversationLimit(), ts(now), tenantId);
    }

    public void setPendingCheckout(UUID tenantId, String razorpaySubscriptionId, String planId, String checkoutUrl, Instant now) {
        update(tenantId, """
                UPDATE subscriptions SET pending_razorpay_subscription_id = ?, pending_razorpay_plan_id = ?,
                    pending_checkout_url = ?, updated_at = ? WHERE tenant_id = ?""",
                razorpaySubscriptionId, planId, checkoutUrl, ts(now), tenantId);
    }

    /** First charge of a pending checkout makes it the tenant's live Razorpay subscription. */
    public boolean promotePending(UUID tenantId, String razorpaySubscriptionId, Instant now) {
        return update(tenantId, """
                UPDATE subscriptions SET razorpay_subscription_id = pending_razorpay_subscription_id,
                    razorpay_plan_id = pending_razorpay_plan_id, pending_razorpay_subscription_id = NULL,
                    pending_razorpay_plan_id = NULL, pending_checkout_url = NULL, updated_at = ?
                WHERE tenant_id = ? AND pending_razorpay_subscription_id = ?""", ts(now), tenantId, razorpaySubscriptionId) > 0;
    }

    public void clearPending(UUID tenantId, String razorpaySubscriptionId, Instant now) {
        update(tenantId, """
                UPDATE subscriptions SET pending_razorpay_subscription_id = NULL, pending_razorpay_plan_id = NULL,
                    pending_checkout_url = NULL, updated_at = ?
                WHERE tenant_id = ? AND pending_razorpay_subscription_id = ?""", ts(now), tenantId, razorpaySubscriptionId);
    }

    /**
     * Story 7.2 code review: Razorpay event ordering guard. Atomically records {@code eventAt} as the
     * latest applied event; false = an equal-or-newer event was already applied → caller ignores this one.
     * Equal timestamps are allowed (Razorpay emits several events per second).
     */
    public boolean advanceLastEvent(UUID tenantId, Instant eventAt) {
        return update(tenantId, """
                UPDATE subscriptions SET last_event_at = ? WHERE tenant_id = ?
                    AND (last_event_at IS NULL OR last_event_at <= ?)""", ts(eventAt), tenantId, ts(eventAt)) > 0;
    }

    /** Story 7.3 (AC3): only the usage column — never the billing state. */
    public void syncUsage(UUID tenantId, int used) {
        update(tenantId, "UPDATE subscriptions SET conversation_used_this_month = ? WHERE tenant_id = ?", used, tenantId);
    }

    /**
     * Story 7.3 (AC4): new usage month. Compare-and-set on the previous billing date so two
     * concurrent resets (sync job + renewal webhook) can't both apply.
     */
    public boolean resetUsage(UUID tenantId, Instant expectedNextBillingDate, Instant newNextBillingDate,
                              Instant anchor, Instant now) {
        return update(tenantId, """
                UPDATE subscriptions SET conversation_used_this_month = 0, next_billing_date = ?,
                    billing_anchor_at = COALESCE(billing_anchor_at, ?), updated_at = ?
                WHERE tenant_id = ? AND next_billing_date IS NOT DISTINCT FROM ?""",
                ts(newNextBillingDate), ts(anchor), ts(now), tenantId, ts(expectedNextBillingDate)) > 0;
    }

    /** Seeding only (tests / bootstrap). Production writes use the targeted methods above. */
    public void save(Subscription s) {
        update(s.getTenantId(), """
                INSERT INTO subscriptions (id, tenant_id, tier, status, conversation_used_this_month,
                    conversation_limit, next_billing_date, grace_period_ends_at, suspended_at,
                    razorpay_subscription_id, razorpay_plan_id, pending_razorpay_subscription_id,
                    pending_razorpay_plan_id, pending_checkout_url, last_event_at, billing_anchor_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    tier = EXCLUDED.tier, status = EXCLUDED.status,
                    conversation_used_this_month = EXCLUDED.conversation_used_this_month,
                    conversation_limit = EXCLUDED.conversation_limit, next_billing_date = EXCLUDED.next_billing_date,
                    grace_period_ends_at = EXCLUDED.grace_period_ends_at, suspended_at = EXCLUDED.suspended_at,
                    razorpay_subscription_id = EXCLUDED.razorpay_subscription_id,
                    razorpay_plan_id = EXCLUDED.razorpay_plan_id,
                    pending_razorpay_subscription_id = EXCLUDED.pending_razorpay_subscription_id,
                    pending_razorpay_plan_id = EXCLUDED.pending_razorpay_plan_id,
                    pending_checkout_url = EXCLUDED.pending_checkout_url,
                    last_event_at = EXCLUDED.last_event_at, billing_anchor_at = EXCLUDED.billing_anchor_at,
                    updated_at = EXCLUDED.updated_at
                """, s.getId(), s.getTenantId(), s.getTier().name(), s.getStatus().name(), s.getConversationUsedThisMonth(),
                s.getConversationLimit(), ts(s.getNextBillingDate()), ts(s.getGracePeriodEndsAt()), ts(s.getSuspendedAt()),
                s.getRazorpaySubscriptionId(), s.getRazorpayPlanId(), s.getPendingRazorpaySubscriptionId(),
                s.getPendingRazorpayPlanId(), s.getPendingCheckoutUrl(), ts(s.getLastEventAt()), ts(s.getBillingAnchorAt()),
                ts(s.getCreatedAt()), ts(s.getUpdatedAt()));
    }

    private int update(UUID tenantId, String sql, Object... params) {
        return inTenant(tenantId, connection -> {
            try (PreparedStatement st = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    Object p = params[i];
                    if (p == null) {
                        st.setNull(i + 1, Types.NULL);
                    } else if (p instanceof Integer n) {
                        st.setInt(i + 1, n);
                    } else {
                        st.setObject(i + 1, p);
                    }
                }
                return st.executeUpdate();
            }
        });
    }

    private <T> T inTenant(UUID tenantId, SqlWork<T> work) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            String previous = currentTenant(connection);
            setTenant(connection, tenantId.toString());
            T result;
            try {
                result = work.run(connection);
            } catch (SQLException | RuntimeException ex) {
                // Code review: in an aborted transaction the restore itself fails — never let that
                // replace the real error.
                try {
                    restoreTenant(connection, previous);
                } catch (SQLException restoreFailure) {
                    ex.addSuppressed(restoreFailure);
                }
                throw ex;
            }
            restoreTenant(connection, previous);
            return result;
        });
    }

    private static void restoreTenant(Connection connection, String previous) throws SQLException {
        if (previous == null || previous.isEmpty()) {
            try (var st = connection.createStatement()) {
                st.execute("RESET app.current_tenant");
            }
        } else {
            setTenant(connection, previous);
        }
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
                rs.getString("pending_razorpay_subscription_id"),
                rs.getString("pending_razorpay_plan_id"),
                rs.getString("pending_checkout_url"),
                instant(rs.getTimestamp("last_event_at")),
                instant(rs.getTimestamp("billing_anchor_at")),
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
