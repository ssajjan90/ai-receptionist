package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.whatsapp.domain.MessageDirection;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminTenantActionsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired WhatsAppNotificationService notificationService;

    @TestConfiguration
    static class MockAiConfig {
        @Bean
        @Primary
        AiChatPort mockAiChatPort() {
            return (system, user) -> new AiResponseResult("Test response from AI", 0.95);
        }

        // Code review (2026-09-01, AC5): a Mockito mock so admin-action owner notifications
        // (suspend/reactivate/terminate) can be verified without a real WhatsApp API call —
        // mirrors the mockAiChatPort() pattern above.
        @Bean
        @Primary
        WhatsAppNotificationService mockWhatsAppNotificationService() {
            return mock(WhatsAppNotificationService.class);
        }
    }

    private UUID seedLiveTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Actions Test Business", "+91" + System.nanoTime() % 10_000_000_000L,
                "+91" + (System.nanoTime() + 1) % 10_000_000_000L);
        return tenantId;
    }

    private String adminToken() throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PLATFORM_ADMIN", "PRO");
    }

    @Test
    void suspendRejectsInboundMessagesThenReactivateResumesAiProcessing() throws Exception {
        UUID tenantId = seedLiveTenant();
        String customerPhone = "+919876500001";

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/suspend", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), customerPhone, "What is the price?", "PHONE_NUM_1", Instant.now()));

        await().atMost(15, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = messageRepository.findAll().stream()
                    .filter(m -> m.getTenantId().equals(tenantId) && m.getDirection() == MessageDirection.OUTBOUND)
                    .toList();
            assertThat(outbound).hasSize(1);
            assertThat(outbound.get(0).getContent())
                    .isEqualTo("This service is temporarily unavailable. Please try again later.");
        });

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/reactivate", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LIVE"));

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), customerPhone, "What is the price?", "PHONE_NUM_1", Instant.now()));

        await().atMost(15, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = messageRepository.findAll().stream()
                    .filter(m -> m.getTenantId().equals(tenantId) && m.getDirection() == MessageDirection.OUTBOUND)
                    .toList();
            assertThat(outbound).hasSize(2);
            assertThat(outbound.get(1).getContent()).isEqualTo("Test response from AI");
        });
    }

    @Test
    void suspendReactivateAndTerminateAreAudited() throws Exception {
        UUID tenantId = seedLiveTenant();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/suspend", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/admin/tenants/{tenantId}/reactivate", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/v1/admin/tenants/{tenantId}", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(tenantId, "ADMIN_SUSPEND", before))
                .isEqualTo(1L);
        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(tenantId, "ADMIN_REACTIVATE", before))
                .isEqualTo(1L);
        assertThat(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(tenantId, "ADMIN_TERMINATE", before))
                .isEqualTo(1L);

        // AC5 (code review, 2026-09-01): the owner is notified for every one of the three actions.
        verify(notificationService).sendMessage(eq(tenantId.toString()), anyString(),
                eq("⚠️ Your CallSahayak service has been temporarily suspended. Please contact support."));
        verify(notificationService).sendMessage(eq(tenantId.toString()), anyString(),
                eq("✅ Your CallSahayak service has been reactivated. Welcome back!"));
        verify(notificationService).sendMessage(eq(tenantId.toString()), anyString(),
                eq("Your CallSahayak account has been terminated. Data will be retained for 30 days then permanently deleted."));
    }

    @Test
    void terminateSchedulesRetentionWindowThirtyDaysOutAndSetsTerminatedStatus() throws Exception {
        UUID tenantId = seedLiveTenant();

        mockMvc.perform(delete("/v1/admin/tenants/{tenantId}", tenantId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        // tenants carries no RLS policy (V1) — a plain JdbcTemplate query is fine here.
        Timestamp scheduledAt = jdbcTemplate.queryForObject(
                "SELECT termination_scheduled_at FROM tenants WHERE id = ?", Timestamp.class, tenantId);
        assertThat(scheduledAt).isNotNull();
        assertThat(scheduledAt.toInstant())
                .isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));

        String status = jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        assertThat(status).isEqualTo("TERMINATED");
    }
}
