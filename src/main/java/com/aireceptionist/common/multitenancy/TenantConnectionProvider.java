package com.aireceptionist.common.multitenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private static final Logger log = LoggerFactory.getLogger(TenantConnectionProvider.class);

    private final DataSource dataSource;

    public TenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("RESET app.current_tenant");
        } catch (SQLException e) {
            log.warn("Failed to reset app.current_tenant in releaseAnyConnection; discarding connection", e);
            try { connection.close(); } catch (SQLException ignored) {}
            throw e;
        }
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        if (tenantIdentifier != null && !TenantIdentifierResolver.DEFAULT_TENANT.equals(tenantIdentifier)) {
            try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
                stmt.setString(1, tenantIdentifier);
                stmt.execute();
            } catch (SQLException e) {
                releaseAnyConnection(connection);
                throw e;
            }
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("RESET app.current_tenant");
        } catch (SQLException e) {
            log.warn("Failed to reset app.current_tenant; discarding connection to prevent tenant context leak", e);
            try { connection.close(); } catch (SQLException ignored) {}
            throw e;
        }
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> X unwrap(Class<X> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (X) this;
        }
        throw new IllegalArgumentException("Cannot unwrap to: " + unwrapType.getName());
    }
}
