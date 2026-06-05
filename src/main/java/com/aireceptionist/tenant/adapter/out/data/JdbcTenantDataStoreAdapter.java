package com.aireceptionist.tenant.adapter.out.data;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.tenant.domain.TenantDataExport;
import com.aireceptionist.tenant.port.out.TenantDataStorePort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JdbcTenantDataStoreAdapter implements TenantDataStorePort {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcTenantDataStoreAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void eraseTenantData(UUID tenantId) {
        withTenantContext(tenantId, () -> jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try {
                setTenant(connection, tenantId);
                deleteTenantRows(connection, "subscriptions", tenantId);
                deleteTenantRows(connection, "voice_calls", tenantId);
                deleteTenantRows(connection, "whatsapp_messages", tenantId);
                deleteTenantRows(connection, "leads", tenantId);
                deleteTenantRows(connection, "knowledge_entries", tenantId);
            } finally {
                resetTenant(connection);
            }
            return null;
        }));
    }

    @Override
    public TenantDataExport exportTenantData(UUID tenantId) {
        return withTenantContext(tenantId, () -> jdbcTemplate.execute(
                (ConnectionCallback<TenantDataExport>) connection -> {
                    try {
                        setTenant(connection, tenantId);
                        List<Map<String, Object>> knowledgeEntries = queryForMaps(connection, """
                                SELECT id, question, product_name, answer, price, entry_type, source, created_at, updated_at
                                FROM knowledge_entries
                                WHERE tenant_id = '%s'
                                ORDER BY created_at, id
                                """.formatted(tenantId));
                        List<Map<String, Object>> leads = queryForMaps(connection, """
                                SELECT id, name, phone, intent, channel, consent_timestamp, consent_channel, created_at
                                FROM leads
                                WHERE tenant_id = '%s' AND erased = FALSE
                                ORDER BY created_at, id
                                """.formatted(tenantId));
                        List<String> messageHashes = queryForStrings(connection, """
                                SELECT content
                                FROM whatsapp_messages
                                WHERE tenant_id = '%s'
                                ORDER BY received_at, id
                                """.formatted(tenantId)).stream().map(this::sha256Hex).toList();
                        return new TenantDataExport(
                                tenantId,
                                Instant.now(clock),
                                knowledgeEntries,
                                leads,
                                messageHashes
                        );
                    } finally {
                        resetTenant(connection);
                    }
                }));
    }

    private <T> T withTenantContext(UUID tenantId, TenantWork<T> work) {
        String previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            return work.execute();
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previousTenant);
            }
        }
    }

    private void setTenant(Connection connection, UUID tenantId) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
        }
    }

    private void resetTenant(Connection connection) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET app.current_tenant");
        }
    }

    private void deleteTenantRows(Connection connection, String table, UUID tenantId) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE tenant_id = ?")) {
            statement.setObject(1, tenantId);
            statement.executeUpdate();
        }
    }

    private List<Map<String, Object>> queryForMaps(Connection connection, String sql) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    Object value = resultSet.getObject(column);
                    if (value instanceof Timestamp timestamp) {
                        value = timestamp.toInstant();
                    }
                    row.put(metaData.getColumnLabel(column), value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private List<String> queryForStrings(Connection connection, String sql) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }

    @FunctionalInterface
    private interface TenantWork<T> {
        T execute();
    }
}
