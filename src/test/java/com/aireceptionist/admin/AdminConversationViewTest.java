package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.LeadService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminConversationViewTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired LeadService leadService;
    @Autowired LeadRepository leadRepository;

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Conversation Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        return tenantId;
    }

    private void seedMessages(UUID tenantId, int count, Instant receivedAt) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            setTenant(connection, tenantId);
            for (int i = 0; i < count; i++) {
                insertMessage(connection, tenantId, "+9190000" + String.format("%05d", i), "Message " + i, receivedAt);
            }
            resetTenant(connection);
            return null;
        });
    }

    private void insertMessage(Connection connection, UUID tenantId, String senderPhone, String content,
                               Instant receivedAt) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO whatsapp_messages
                    (id, tenant_id, message_id, sender_type, sender_phone, content, direction, received_at)
                VALUES (?, ?, ?, 'CUSTOMER', ?, ?, 'INBOUND', ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setString(3, "wamid-" + UUID.randomUUID());
            statement.setString(4, senderPhone);
            statement.setString(5, content);
            statement.setTimestamp(6, Timestamp.from(receivedAt));
            statement.executeUpdate();
        }
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

    private String adminToken() throws Exception {
        return adminToken(UUID.randomUUID());
    }

    private String adminToken(UUID adminId) throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), adminId.toString(),
                "PLATFORM_ADMIN", "PRO");
    }

    @Test
    void returnsOnlyTheRequestedTenantsMessages() throws Exception {
        UUID tenantA = seedTenant();
        UUID tenantB = seedTenant();
        seedMessages(tenantA, 10, Instant.now());

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantA)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(10))
                .andExpect(jsonPath("$.data.content.length()").value(10));

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantB)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void dateRangeFilterNarrowsResults() throws Exception {
        UUID tenantId = seedTenant();
        Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        seedMessages(tenantId, 3, old);
        seedMessages(tenantId, 2, recent);

        // from alone: excludes the 3 old messages.
        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .param("from", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // to alone (code review, 2026-09-01 — AC4's upper bound was previously untested):
        // excludes the 2 recent messages.
        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .param("to", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));

        // from + to together: isolates just the 2 recent messages.
        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .param("from", Instant.now().minus(2, ChronoUnit.HOURS).toString())
                        .param("to", Instant.now().toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void fromAfterToIsRejected() throws Exception {
        UUID tenantId = seedTenant();

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .param("from", Instant.now().toString())
                        .param("to", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTenantIsRejectedWithNotFound() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewingConversationsWritesAdminConversationViewAuditLogNamingTheActingAdmin() throws Exception {
        UUID tenantId = seedTenant();
        UUID adminId = UUID.randomUUID();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        seedMessages(tenantId, 1, Instant.now());

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .header("Authorization", "Bearer " + adminToken(adminId)))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, "ADMIN_CONVERSATION_VIEW", before)).isEqualTo(1L);

        // AC3 / Task 1's stated rationale for adding adminId: the acting admin must be recorded,
        // not just that *some* admin viewed the conversation (code review, 2026-09-01).
        String messageHash = jdbcTemplate.queryForObject(
                "SELECT message_hash FROM audit_log WHERE tenant_id = ? AND event_type = 'ADMIN_CONVERSATION_VIEW' AND occurred_at > ?",
                String.class, tenantId, Timestamp.from(before));
        assertThat(messageHash).isEqualTo(adminId.toString());
    }

    @Test
    void erasedLeadsMessageContentIsRedactedButOtherMessagesAreNot() throws Exception {
        UUID tenantId = seedTenant();
        String erasedPhone = "+919876543210";
        String activePhone = "+919876500000";

        UUID erasedLeadId;
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            erasedLeadId = leadRepository.save(Lead.create(tenantId, "Erased Customer", erasedPhone,
                    "Some intent", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now())).getId();
        } finally {
            TenantContext.clear();
        }
        leadService.eraseLead(tenantId, erasedLeadId);

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            setTenant(connection, tenantId);
            insertMessage(connection, tenantId, erasedPhone, "Secret complaint content", Instant.now());
            insertMessage(connection, tenantId, activePhone, "Ordinary question", Instant.now());
            resetTenant(connection);
            return null;
        });

        MvcResult result = mockMvc.perform(get("/v1/admin/tenants/{tenantId}/conversations", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> content = JsonPath.read(result.getResponse().getContentAsString(), "$.data.content");
        assertThat(content).hasSize(2);
        assertThat(content).anySatisfy(m -> assertThat(m.get("content")).isNull());
        assertThat(content).anySatisfy(m -> assertThat(m.get("content")).isEqualTo("Ordinary question"));
    }
}
