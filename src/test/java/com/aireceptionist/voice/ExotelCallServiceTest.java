package com.aireceptionist.voice;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.domain.VoiceCallStatus;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story 6.1 (AC4) unit/integration coverage for the {@code @ApplicationModuleListener} stub —
 * the async-listener DB-write equivalent of {@code WhatsAppMessageService.onInboundMessage}'s test
 * coverage. Not named in Task 6's file list (only {@code VoiceWebhookControllerTest} is), but the
 * dev-story workflow's own definition-of-done requires unit tests for new business logic, and
 * {@code ExotelCallService} is untested otherwise.
 */
class ExotelCallServiceTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired VoiceCallRepository voiceCallRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Voice Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        return tenantId;
    }

    @Test
    void onVoiceCallReceivedPersistsVoiceCallRow() {
        UUID tenantId = seedTenant();
        String callSid = "CA-" + UUID.randomUUID();

        eventPublisher.publishEvent(new VoiceCallReceivedEvent(
                tenantId.toString(), callSid, "+919876543210", "+911234567890"));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<VoiceCall> saved = voiceCallRepository.findByCallSid(callSid);
            assertThat(saved).isPresent();
            assertThat(saved.get().getTenantId()).isEqualTo(tenantId);
            assertThat(saved.get().getCallerPhone()).isEqualTo("+919876543210");
            assertThat(saved.get().getStatus()).isEqualTo(VoiceCallStatus.RECEIVED);
            assertThat(saved.get().getStartedAt()).isNotNull();
        });
    }
}
