package com.aireceptionist.ai;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.whatsapp.domain.MessageDirection;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

class FrustrationModeIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired StringRedisTemplate redisTemplate;

    static final AtomicReference<String> capturedSystemPrompt = new AtomicReference<>("");

    @TestConfiguration
    static class CapturingAiConfig {
        @Bean
        @Primary
        AiChatPort capturingAiChatPort() {
            return (system, user) -> {
                capturedSystemPrompt.set(system);
                return new AiResponseResult("Test AI response", 0.90);
            };
        }
    }

    @Test
    void frustrationKeywordSetsEmpathyModeInRedis() {
        UUID tenantId = UUID.randomUUID();
        String phone = "+919111000001";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone,
                "This product cheated me and I want a refund!", "PHONE_F01", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = outboundFor(tenantId);
            assertThat(outbound).hasSize(1);
            assertThat(capturedSystemPrompt.get()).contains("frustrated");
        });
    }

    @Test
    void empathyModePersistsForFollowUpMessage() {
        UUID tenantId = UUID.randomUUID();
        String phone = "+919111000002";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone,
                "I want to complain about my order", "PHONE_F02", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(outboundFor(tenantId)).hasSize(1));

        capturedSystemPrompt.set("");

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone,
                "Can you help me with this?", "PHONE_F03", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(outboundFor(tenantId)).hasSize(2);
            assertThat(capturedSystemPrompt.get()).contains("frustrated");
        });
    }

    @Test
    void normalMessageDoesNotUseEmpathyPrompt() {
        UUID tenantId = UUID.randomUUID();
        String phone = "+919111000003";
        capturedSystemPrompt.set("");

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone,
                "What is the price of Samsung S24?", "PHONE_F04", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(outboundFor(tenantId)).hasSize(1);
            assertThat(capturedSystemPrompt.get()).doesNotContain("frustrated");
        });
    }

    private List<WhatsAppMessage> outboundFor(UUID tenantId) {
        return messageRepository.findAll().stream()
                .filter(m -> m.getTenantId().equals(tenantId)
                        && m.getDirection() == MessageDirection.OUTBOUND)
                .toList();
    }
}
