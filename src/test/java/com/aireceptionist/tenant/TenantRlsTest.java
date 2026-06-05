package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRlsTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void tenantIsolationViaRls() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Insert tenants — tenants table has no RLS, plain insert works
        // Phone numbers derived from the tenant UUID to guarantee uniqueness across parallel/repeated runs
        jdbcTemplate.update(
                "INSERT INTO tenants(id, business_name, phone_number) VALUES (?, ?, ?)",
                tenantA, "Shop A", "+91-" + tenantA.toString().replace("-", "").substring(0, 10));
        jdbcTemplate.update(
                "INSERT INTO tenants(id, business_name, phone_number) VALUES (?, ?, ?)",
                tenantB, "Shop B", "+91-" + tenantB.toString().replace("-", "").substring(0, 10));

        // Insert knowledge entries — knowledge_entries has FORCE RLS, must SET tenant first.
        // ConnectionCallback keeps SET + INSERT on the same physical connection.
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            conn.createStatement().execute(
                    "SELECT set_config('app.current_tenant', '" + tenantA + "', false)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_entries(id, tenant_id, question, answer) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantA);
                ps.setString(3, "Return policy?");
                ps.setString(4, "30 days.");
                ps.execute();
            }

            conn.createStatement().execute(
                    "SELECT set_config('app.current_tenant', '" + tenantB + "', false)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_entries(id, tenant_id, question, answer) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantB);
                ps.setString(3, "Opening hours?");
                ps.setString(4, "9am to 9pm.");
                ps.execute();
            }

            conn.createStatement().execute("RESET app.current_tenant");
            return null;
        });

        // Verify: Tenant A context → only Tenant A's row visible
        List<String> tenantARows = jdbcTemplate.execute((ConnectionCallback<List<String>>) conn -> {
            conn.createStatement().execute("SET app.current_tenant = '" + tenantA + "'");
            var rs = conn.createStatement().executeQuery("SELECT tenant_id::text FROM knowledge_entries");
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString(1));
            conn.createStatement().execute("RESET app.current_tenant");
            return result;
        });
        assertThat(tenantARows).hasSize(1);
        assertThat(tenantARows.get(0)).isEqualTo(tenantA.toString());

        // Verify: Tenant B context → only Tenant B's row visible
        List<String> tenantBRows = jdbcTemplate.execute((ConnectionCallback<List<String>>) conn -> {
            conn.createStatement().execute("SET app.current_tenant = '" + tenantB + "'");
            var rs = conn.createStatement().executeQuery("SELECT tenant_id::text FROM knowledge_entries");
            List<String> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getString(1));
            conn.createStatement().execute("RESET app.current_tenant");
            return result;
        });
        assertThat(tenantBRows).hasSize(1);
        assertThat(tenantBRows.get(0)).isEqualTo(tenantB.toString());

        // Verify: no tenant set → zero rows visible (secure default)
        List<Object> noTenantRows = jdbcTemplate.execute((ConnectionCallback<List<Object>>) conn -> {
            conn.createStatement().execute("RESET app.current_tenant");
            var rs = conn.createStatement().executeQuery("SELECT id FROM knowledge_entries");
            List<Object> result = new ArrayList<>();
            while (rs.next()) result.add(rs.getObject("id"));
            return result;
        });
        assertThat(noTenantRows).isEmpty();
    }
}
