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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

class AiPipelineTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired StringRedisTemplate redisTemplate;

    @TestConfiguration
    static class MockAiConfig {
        @Bean
        @Primary
        AiChatPort mockAiChatPort() {
            return (system, user) -> new AiResponseResult("Test response from AI", 0.95);
        }
    }

    @Test
    void inboundMessageTriggersOutboundAiResponsePersisted() {
        UUID tenantId = UUID.randomUUID();
        String phone = "+919876543210";
        String messageId = UUID.randomUUID().toString();

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, messageId, phone, "What is the price?", "PHONE_NUM_123", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = messageRepository.findAll().stream()
                    .filter(m -> m.getTenantId().equals(tenantId)
                            && m.getDirection() == MessageDirection.OUTBOUND)
                    .toList();
            assertThat(outbound).hasSize(1);
            assertThat(outbound.get(0).getContent()).isEqualTo("Test response from AI");
            assertThat(outbound.get(0).getConfidenceScore()).isNotNull();
        });
    }

    @Test
    void secondIdenticalQueryReturnsFromRedisCache() {
        UUID tenantId = UUID.randomUUID();
        String phone = "+919876543211";
        String messageId1 = UUID.randomUUID().toString();
        String messageId2 = UUID.randomUUID().toString();

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, messageId1, phone, "price of Samsung S24", "PHONE_NUM_456", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> msgs = messageRepository.findAll().stream()
                    .filter(m -> m.getTenantId().equals(tenantId)
                            && m.getDirection() == MessageDirection.OUTBOUND)
                    .toList();
            assertThat(msgs).isNotEmpty();
        });

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, messageId2, phone, "price of Samsung S24", "PHONE_NUM_456", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outboundMsgs = messageRepository.findAll().stream()
                    .filter(m -> m.getTenantId().equals(tenantId)
                            && m.getDirection() == MessageDirection.OUTBOUND)
                    .toList();
            assertThat(outboundMsgs).hasSize(2);
            assertThat(outboundMsgs.get(0).getContent())
                    .isEqualTo(outboundMsgs.get(1).getContent());
        });
    }
}
