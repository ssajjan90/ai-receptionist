package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.tenant.domain.TenantDataExport;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantServiceEraseTest extends AbstractIntegrationTest {

    @Autowired
    TenantDataRightsUseCase tenantService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void exportTenantDataReturnsTenantScopedDataAndEraseHardDeletesTenantData() {
        UUID tenantId = UUID.randomUUID();
        insertTenantData(tenantId);

        TenantDataExport export = tenantService.exportTenantData(tenantId);

        assertThat(export.tenantId()).isEqualTo(tenantId);
        assertThat(export.knowledgeEntries()).hasSize(1);
        assertThat(export.leads()).hasSize(1);
        assertThat(export.messageHashes()).singleElement().satisfies(hash -> assertThat(hash).hasSize(64));

        tenantService.eraseTenantData(tenantId);

        assertThat(countTenantRows(tenantId, "knowledge_entries")).isZero();
        assertThat(countTenantRows(tenantId, "leads")).isZero();
        assertThat(countTenantRows(tenantId, "whatsapp_messages")).isZero();
        assertThat(countTenantRows(tenantId, "voice_calls")).isZero();
        assertThat(countTenantRows(tenantId, "subscriptions")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(1);
    }

    private void insertTenantData(UUID tenantId) {
        jdbcTemplate.update(
                "INSERT INTO tenants(id, business_name, phone_number) VALUES (?, ?, ?)",
                tenantId,
                "Shop " + tenantId,
                "+91-" + tenantId.toString().replace("-", "").substring(0, 10)
        );
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            connection.createStatement().execute(
                    "SELECT set_config('app.current_tenant', '" + tenantId + "', false)");

            insert(connection.prepareStatement("""
                    INSERT INTO knowledge_entries(id, tenant_id, question, answer)
                    VALUES (?, ?, ?, ?)
                    """), List.of(UUID.randomUUID(), tenantId, "Hours?", "9am to 9pm"));
            insert(connection.prepareStatement("""
                    INSERT INTO leads(id, tenant_id, name, phone, intent, channel, consent_timestamp, consent_channel)
                    VALUES (?, ?, ?, ?, ?, 'whatsapp', NOW(), 'whatsapp')
                    """), List.of(UUID.randomUUID(), tenantId, "Priya", "+919000000001", "pricing"));
            insert(connection.prepareStatement("""
                    INSERT INTO whatsapp_messages(id, tenant_id, message_id, sender_type, sender_phone, content)
                    VALUES (?, ?, ?, 'CUSTOMER', ?, ?)
                    """), List.of(UUID.randomUUID(), tenantId, "wamid-" + tenantId, "+919000000001", "Need price list"));
            insert(connection.prepareStatement("""
                    INSERT INTO voice_calls(id, tenant_id, call_sid, caller_phone)
                    VALUES (?, ?, ?, ?)
                    """), List.of(UUID.randomUUID(), tenantId, "CA" + tenantId.toString().replace("-", ""), "+919000000001"));
            insert(connection.prepareStatement("""
                    INSERT INTO subscriptions(id, tenant_id, tier, status)
                    VALUES (?, ?, 'BASIC', 'ACTIVE')
                    """), List.of(UUID.randomUUID(), tenantId));
            return null;
        });
    }

    private void insert(PreparedStatement statement, List<Object> values) throws java.sql.SQLException {
        try (statement) {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
    }

    private int countTenantRows(UUID tenantId, String table) {
        return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            connection.createStatement().execute(
                    "SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
            var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table);
            resultSet.next();
            return resultSet.getInt(1);
        });
    }
}
