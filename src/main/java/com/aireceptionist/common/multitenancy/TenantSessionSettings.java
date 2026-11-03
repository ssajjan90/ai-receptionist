package com.aireceptionist.common.multitenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Helpers for JDBC code that scopes a statement to a tenant via Postgres' session setting
 * {@code app.current_tenant} (RLS).
 *
 * <p>Always <b>restore the previous value</b> — never blindly {@code RESET}. Inside a Spring
 * transaction, JdbcTemplate runs on the transaction's own connection, which
 * {@link TenantConnectionProvider} already scoped to the transaction's tenant. A {@code RESET}
 * there wipes that scope for the rest of the transaction, so every RLS-protected row Hibernate
 * flushes at commit is rejected ("new row violates row-level security policy"). That is exactly
 * what made every WhatsApp AI reply's transaction fail after {@code AuditLogRepository.save}
 * (called by the AI confidence gate) reset the setting mid-listener — the async-listener test
 * timeouts that were long blamed on the environment.
 */
public final class TenantSessionSettings {

    private TenantSessionSettings() {
    }

    /** The connection's current {@code app.current_tenant}, or null/empty if unset. */
    public static String current(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    public static void set(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            st.setString(1, tenantId);
            st.execute();
        }
    }

    /** Puts back what {@link #current} returned before the caller changed it. */
    public static void restore(Connection connection, String previous) throws SQLException {
        if (previous == null || previous.isEmpty()) {
            try (Statement st = connection.createStatement()) {
                st.execute("RESET app.current_tenant");
            }
        } else {
            set(connection, previous);
        }
    }
}
