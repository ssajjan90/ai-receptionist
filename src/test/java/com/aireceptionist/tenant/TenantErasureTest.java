package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 5.5 (AC3-AC6). {@code TenantDataRightsUseCase.eraseTenantData} (the raw data deletion)
 * is already covered at the service layer by {@code TenantServiceEraseTest} — new coverage here:
 * the owner-facing HTTP endpoint, ownership enforcement, and the AC3/AC4-specific requirement
 * this story adds on top — transitioning tenant status to ERASED atomically with the deletion
 * (which {@code TenantServiceEraseTest}'s direct {@code eraseTenantData()} call never did; that
 * status transition is new to {@code TenantLifecycleUseCase.eraseNow}, story 5.5's addition).
 */
@AutoConfigureMockMvc
class TenantErasureTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired AuditLogRepository auditLogRepository;
    @MockitoSpyBean TenantRegistrationRepository tenantRegistrationRepository;

    private UUID seedTenantWithData() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Erasure Test Business", "+91" + System.nanoTime() % 10_000_000_000L,
                "+91" + (System.nanoTime() + 1) % 10_000_000_000L);

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            setTenant(connection, tenantId);
            insert(connection, "INSERT INTO knowledge_entries (tenant_id, question, answer) VALUES (?, ?, ?)",
                    tenantId, "What are your hours?", "9am to 9pm");
            insert(connection, """
                    INSERT INTO leads (id, tenant_id, name, phone, intent, channel, consent_timestamp, consent_channel)
                    VALUES (?, ?, ?, ?, ?, 'WHATSAPP', NOW(), 'WHATSAPP')
                    """, UUID.randomUUID(), tenantId, "Some Lead", "+919876500003", "pricing");
            insert(connection, """
                    INSERT INTO whatsapp_messages (id, tenant_id, message_id, sender_type, sender_phone, content)
                    VALUES (?, ?, ?, 'CUSTOMER', ?, ?)
                    """, UUID.randomUUID(), tenantId, "wamid-" + UUID.randomUUID(), "+919876500003", "Some message");
            resetTenant(connection);
            return null;
        });
        return tenantId;
    }

    private void setTenant(Connection connection, UUID tenantId) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private void resetTenant(Connection connection) throws java.sql.SQLException {
        connection.createStatement().execute("RESET app.current_tenant");
    }

    private void insert(Connection connection, String sql, Object... params) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }

    private String ownerToken(UUID tenantId) throws Exception {
        return tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");
    }

    // Every one of these tables carries RLS (V8/W99): an unscoped query correctly sees zero rows
    // regardless of what's actually there, so this must set app.current_tenant first or the
    // "still there" assertions below would be trivially satisfied by RLS hiding the rows, not by
    // genuinely proving they survived.
    private long countRows(String table, UUID tenantId) {
        Long count = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try {
                setTenant(connection, tenantId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?")) {
                    statement.setObject(1, tenantId);
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getLong(1);
                    }
                }
            } finally {
                resetTenant(connection);
            }
        });
        return count == null ? -1 : count;
    }

    @Test
    void ownerErasureHardDeletesAllDataSetsStatusErasedAndWritesAuditLog() throws Exception {
        UUID tenantId = seedTenantWithData();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        mockMvc.perform(delete("/v1/tenants/{tenantId}", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(tenantId)))
                .andExpect(status().isNoContent());

        // AC6: explicit zero-rows check on knowledge_entries.
        assertThat(countRows("knowledge_entries", tenantId)).isZero();
        assertThat(countRows("leads", tenantId)).isZero();
        assertThat(countRows("whatsapp_messages", tenantId)).isZero();

        // Tenant row itself survives, transitioned to ERASED (AC3) — not hard-deleted.
        String status = jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        assertThat(status).isEqualTo("ERASED");

        // AC5: erasure completion audited.
        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, "TENANT_DATA_ERASED", before)).isEqualTo(1L);
    }

    @Test
    void ownerCannotEraseAnotherTenantsData() throws Exception {
        UUID tenantId = seedTenantWithData();
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(delete("/v1/tenants/{tenantId}", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(otherTenantId)))
                .andExpect(status().isForbidden());

        // Nothing erased — the forbidden request must not have touched the tenant's data.
        assertThat(countRows("knowledge_entries", tenantId)).isEqualTo(1);
        String status = jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        assertThat(status).isEqualTo("LIVE");
    }

    @Test
    void erasureRollsBackFullyWhenStatusTransitionFails() throws Exception {
        // Story 5.5 code review (AC4): the annotation alone doesn't prove atomicity — force a
        // failure in the step *after* eraseTenantData's deletes have already run within the same
        // @Transactional method, and verify Spring's rollback actually undoes those deletes too.
        UUID tenantId = seedTenantWithData();
        doThrow(new RuntimeException("Simulated failure during status transition"))
                .when(tenantRegistrationRepository)
                .save(argThat((BusinessTenant tenant) -> tenant != null && tenantId.equals(tenant.getId())));

        mockMvc.perform(delete("/v1/tenants/{tenantId}", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(tenantId)))
                .andExpect(status().is5xxServerError());

        assertThat(countRows("knowledge_entries", tenantId)).isEqualTo(1);
        assertThat(countRows("leads", tenantId)).isEqualTo(1);
        assertThat(countRows("whatsapp_messages", tenantId)).isEqualTo(1);
        String status = jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        assertThat(status).isEqualTo("LIVE");
    }

    @Test
    void erasingUnknownTenantReturnsNotFound() throws Exception {
        UUID unknownTenantId = UUID.randomUUID();

        mockMvc.perform(delete("/v1/tenants/{tenantId}", unknownTenantId)
                        .header("Authorization", "Bearer " + ownerToken(unknownTenantId)))
                .andExpect(status().isNotFound());
    }
}
