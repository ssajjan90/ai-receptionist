package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 5.5 (AC1, AC2). {@code TenantDataRightsUseCase.exportTenantData} itself is already
 * covered at the service layer by {@code TenantServiceEraseTest} — these tests are new HTTP-layer
 * coverage: ownership enforcement on the owner-facing endpoint, and the admin-facing endpoint's
 * own audit trail, neither of which existed before this story.
 */
@AutoConfigureMockMvc
class TenantDataExportTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired AuditLogRepository auditLogRepository;
    @Value("${app.export.message-hash-secret}") String messageHashSecret;

    private UUID seedTenantWithData() throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Export Test Business", "+91" + System.nanoTime() % 10_000_000_000L,
                "+91" + (System.nanoTime() + 1) % 10_000_000_000L);

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            setTenant(connection, tenantId);
            insert(connection, "INSERT INTO knowledge_entries (tenant_id, question, answer) VALUES (?, ?, ?)",
                    tenantId, "What are your hours?", "9am to 9pm");
            insert(connection, """
                    INSERT INTO leads (id, tenant_id, name, phone, intent, channel, consent_timestamp, consent_channel)
                    VALUES (?, ?, ?, ?, ?, 'WHATSAPP', NOW(), 'WHATSAPP')
                    """, UUID.randomUUID(), tenantId, "Active Lead", "+919876500001", "pricing");
            UUID erasedLeadId = UUID.randomUUID();
            insert(connection, """
                    INSERT INTO leads (id, tenant_id, name, phone, intent, channel, consent_timestamp, consent_channel)
                    VALUES (?, ?, ?, ?, ?, 'WHATSAPP', NOW(), 'WHATSAPP')
                    """, erasedLeadId, tenantId, "Erased Lead", "+919876500002", "pricing");
            insert(connection, "UPDATE leads SET erased = TRUE, name = NULL, phone = NULL WHERE id = ?", erasedLeadId);
            insert(connection, """
                    INSERT INTO whatsapp_messages (id, tenant_id, message_id, sender_type, sender_phone, content)
                    VALUES (?, ?, ?, 'CUSTOMER', ?, ?)
                    """, UUID.randomUUID(), tenantId, "wamid-" + UUID.randomUUID(), "+919876500001", "Secret raw content");
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

    private String hmacSha256Hex(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(messageHashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String ownerToken(UUID tenantId) throws Exception {
        return tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");
    }

    private String adminToken() throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PLATFORM_ADMIN", "PRO");
    }

    @Test
    void ownerExportReturnsKbEntriesNonErasedLeadsAndMessageHashesNotRawContent() throws Exception {
        UUID tenantId = seedTenantWithData();

        mockMvc.perform(get("/v1/tenants/{tenantId}/export", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeEntries.length()").value(1))
                .andExpect(jsonPath("$.data.leads.length()").value(1))
                .andExpect(jsonPath("$.data.leads[0].name").value("Active Lead"))
                .andExpect(jsonPath("$.data.messageHashes.length()").value(1))
                .andExpect(jsonPath("$.data.messageHashes[0]").value(hmacSha256Hex("Secret raw content")))
                .andExpect(jsonPath("$.data.messageHashes[0]").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Secret raw content"))));
    }

    @Test
    void ownerCannotExportAnotherTenantsData() throws Exception {
        UUID tenantId = seedTenantWithData();
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/tenants/{tenantId}/export", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(otherTenantId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCannotCallAdminExportEndpoint() throws Exception {
        UUID tenantId = seedTenantWithData();

        // Story 5.5 code review: negative-authorization coverage for the admin export endpoint —
        // AdminController's class-level @PreAuthorize("hasRole('PLATFORM_ADMIN')") must reject an
        // OWNER-role token, not just an unauthenticated request.
        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/export", tenantId)
                        .header("Authorization", "Bearer " + ownerToken(tenantId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminExportReturnsSameDataAndWritesAdminDataExportAuditEntry() throws Exception {
        UUID tenantId = seedTenantWithData();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/export", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeEntries.length()").value(1))
                .andExpect(jsonPath("$.data.leads.length()").value(1));

        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, "ADMIN_DATA_EXPORT", before)).isEqualTo(1L);
    }
}
