package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminBroadcastTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WhatsAppNotificationService notificationService;

    @TestConfiguration
    static class MockNotificationConfig {
        @Bean
        @Primary
        WhatsAppNotificationService mockWhatsAppNotificationService() {
            return mock(WhatsAppNotificationService.class);
        }
    }

    private UUID seedTenant(String ownerPhone) {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Broadcast Test Business", "+91" + System.nanoTime() % 10_000_000_000L, ownerPhone);
        return tenantId;
    }

    private String adminToken() throws Exception {
        return adminToken(UUID.randomUUID());
    }

    private String adminToken(UUID adminId) throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), adminId.toString(), "PLATFORM_ADMIN", "PRO");
    }

    @Test
    void notifyTenantSendsMessageAndWritesAuditLogWithMessagePreview() throws Exception {
        UUID tenantId = seedTenant("+919876500001");
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        String longMessage = "A".repeat(80);

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", tenantId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"message\":\"" + longMessage + "\"}"))
                .andExpect(status().isOk());

        verify(notificationService).sendMessage(eq(tenantId.toString()), eq("+919876500001"), eq(longMessage));

        // audit_log carries RLS (V9/W99): an unscoped query sees zero rows regardless of what
        // the app wrote, so app.current_tenant must be set on this connection first.
        String messageHash = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try {
                connection.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT message_hash FROM audit_log WHERE tenant_id = ? AND event_type = 'ADMIN_NOTIFICATION_SENT' AND occurred_at > ?")) {
                    statement.setObject(1, tenantId);
                    statement.setTimestamp(2, java.sql.Timestamp.from(before));
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getString(1);
                    }
                }
            } finally {
                connection.createStatement().execute("RESET app.current_tenant");
            }
        });
        assertThat(messageHash).isEqualTo("A".repeat(50));
    }

    @Test
    void notifyTenantWithNoOwnerPhoneOnFileReturnsNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "No Owner Phone Business", "+91" + System.nanoTime() % 10_000_000_000L);

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", tenantId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OWNER_PHONE_NOT_FOUND"));
    }

    @Test
    void notifyUnknownTenantReturnsNotFound() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void notifyRejectsBlankMessage() throws Exception {
        UUID tenantId = seedTenant("+919876500002");

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", tenantId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void broadcastSendsToAllListedTenantsAndReturnsCounts() throws Exception {
        UUID tenantA = seedTenant("+919876500003");
        UUID tenantB = seedTenant("+919876500004");
        UUID tenantC = seedTenant("+919876500005");

        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("""
                                {"tenantIds": ["%s", "%s", "%s"], "message": "Scheduled maintenance tonight."}
                                """.formatted(tenantA, tenantB, tenantC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(3))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.failedTenantIds.length()").value(0));

        verify(notificationService, times(3)).sendMessage(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), eq("Scheduled maintenance tonight."));
    }

    @Test
    void broadcastCountsUnknownTenantsAsFailedWithoutAbortingTheRest() throws Exception {
        UUID knownTenant = seedTenant("+919876500006");
        UUID unknownTenant = UUID.randomUUID();

        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("""
                                {"tenantIds": ["%s", "%s"], "message": "Hello"}
                                """.formatted(knownTenant, unknownTenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.failedTenantIds[0]").value(unknownTenant.toString()));
    }

    @Test
    void broadcastRejectsMoreThanOneHundredTenantIds() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            if (i > 0) ids.append(",");
            ids.append("\"").append(UUID.randomUUID()).append("\"");
        }

        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"tenantIds\": [" + ids + "], \"message\": \"Too many\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void broadcastRejectsANullTenantIdInTheList() throws Exception {
        UUID tenantId = seedTenant("+91" + System.nanoTime() % 10_000_000_000L);

        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"tenantIds\": [\"" + tenantId + "\", null], \"message\": \"Hi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void broadcastConsumesExactlyOneRateLimitTokenRegardlessOfListSize() throws Exception {
        UUID adminId = UUID.randomUUID();
        String token = adminToken(adminId);

        // First call lists 3 tenants — code review, 2026-09-01: this must still consume only
        // ONE token from the shared per-admin bucket (not one per tenant), otherwise the
        // remaining loop below would exhaust the budget early and this test would falsely pass.
        UUID t1 = seedTenant("+91" + System.nanoTime() % 10_000_000_000L);
        UUID t2 = seedTenant("+91" + (System.nanoTime() + 1) % 10_000_000_000L);
        UUID t3 = seedTenant("+91" + (System.nanoTime() + 2) % 10_000_000_000L);
        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"tenantIds": ["%s", "%s", "%s"], "message": "First batch"}
                                """.formatted(t1, t2, t3)))
                .andExpect(status().isOk());

        for (int i = 0; i < 9; i++) {
            UUID tenantId = seedTenant("+91" + (System.nanoTime() + 10 + i) % 10_000_000_000L);
            mockMvc.perform(post("/v1/admin/broadcast")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"tenantIds\": [\"" + tenantId + "\"], \"message\": \"Batch " + i + "\"}"))
                    .andExpect(status().isOk());
        }

        UUID eleventhTenant = seedTenant("+91" + System.nanoTime() % 10_000_000_000L);
        mockMvc.perform(post("/v1/admin/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"tenantIds\": [\"" + eleventhTenant + "\"], \"message\": \"Eleventh\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void eleventhNotifyWithinAMinuteIsRateLimited() throws Exception {
        UUID adminId = UUID.randomUUID();
        String token = adminToken(adminId);

        for (int i = 0; i < 10; i++) {
            UUID tenantId = seedTenant("+91" + (System.nanoTime() + i) % 10_000_000_000L);
            mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", tenantId)
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"message\":\"Hi " + i + "\"}"))
                    .andExpect(status().isOk());
        }

        UUID eleventhTenant = seedTenant("+91" + System.nanoTime() % 10_000_000_000L);
        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/notify", eleventhTenant)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"message\":\"Eleventh\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }
}
