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

class ConfidenceGatingIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired StringRedisTemplate redisTemplate;

    static final AtomicReference<Double> nextConfidence = new AtomicReference<>(0.90);

    @TestConfiguration
    static class ControllableAiConfig {
        @Bean
        @Primary
        AiChatPort controllableAiChatPort() {
            return (system, user) -> new AiResponseResult("AI response", nextConfidence.get());
        }
    }

    @Test
    void highConfidenceResponseSentDirectlyToCustomer() {
        nextConfidence.set(0.90);
        UUID tenantId = UUID.randomUUID();
        String phone = "+919000000001";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone, "price of iPhone", "PHONE_001", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = outboundFor(tenantId);
            assertThat(outbound).hasSize(1);
            assertThat(outbound.get(0).getContent()).isEqualTo("AI response");
        });
    }

    @Test
    void mediumConfidenceResponseStillSentToCustomer() {
        nextConfidence.set(0.62);
        UUID tenantId = UUID.randomUUID();
        String phone = "+919000000002";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone, "some uncertain query", "PHONE_002", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = outboundFor(tenantId);
            assertThat(outbound).hasSize(1);
            assertThat(outbound.get(0).getContent()).isEqualTo("AI response");
        });
    }

    @Test
    void lowConfidenceResponseReplacedWithFallback() {
        nextConfidence.set(0.24);
        UUID tenantId = UUID.randomUUID();
        String phone = "+919000000003";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), phone, "totally unknown question", "PHONE_003", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WhatsAppMessage> outbound = outboundFor(tenantId);
            assertThat(outbound).hasSize(1);
            assertThat(outbound.get(0).getContent()).doesNotContain("AI response");
            assertThat(outbound.get(0).getContent()).contains("follow up");
        });
    }

    private List<WhatsAppMessage> outboundFor(UUID tenantId) {
        return messageRepository.findAll().stream()
                .filter(m -> m.getTenantId().equals(tenantId)
                        && m.getDirection() == MessageDirection.OUTBOUND)
                .toList();
    }
}
