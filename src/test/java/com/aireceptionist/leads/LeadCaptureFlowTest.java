package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

class LeadCaptureFlowTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired LeadRepository leadRepository;
    @Autowired StringRedisTemplate redisTemplate;

    @TestConfiguration
    static class MockAiConfig {
        @Bean
        @Primary
        AiChatPort mockAiChatPort() {
            return (system, user) -> new AiResponseResult("We have the Samsung Galaxy S24 in stock.", 0.92);
        }
    }

    @BeforeEach
    void clearLeads() {
        leadRepository.deleteAll();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void intentMessageSendsConsentAndLeadPhaseSetToConsentSent() {
        UUID tenantId = UUID.randomUUID();
        String senderPhone = "+919876540001";

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), senderPhone,
                "I want to buy the Samsung phone", "PH_NUM", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            String phaseKey = "conv:" + tenantId + ":" + sha256(senderPhone) + ":lead_capture_phase";
            String phase = redisTemplate.opsForValue().get(phaseKey);
            assertThat(phase).isEqualTo("CONSENT_SENT");
        });
    }

    @Test
    void nameAndPhoneReplyCreatesLeadInDb() {
        UUID tenantId = UUID.randomUUID();
        String senderPhone = "+919876540002";
        String phaseKey = "conv:" + tenantId + ":" + sha256(senderPhone) + ":lead_capture_phase";

        // Simulate: consent already sent
        redisTemplate.opsForValue().set(phaseKey, "CONSENT_SENT");

        // Customer replies with name and phone
        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), senderPhone,
                "Ravi Kumar 9876543210", "PH_NUM", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var leads = leadRepository.findAll().stream()
                    .filter(l -> l.getTenantId().equals(tenantId))
                    .toList();
            assertThat(leads).hasSize(1);
            assertThat(leads.get(0).getPhone()).isEqualTo("9876543210");
            assertThat(leads.get(0).getErased()).isFalse();
            assertThat(leads.get(0).getConsentTimestamp()).isNotNull();
            assertThat(leads.get(0).getStatus()).isEqualTo(LeadStatus.NEW);
        });
    }

    @Test
    void declineDoesNotCreateLead() {
        UUID tenantId = UUID.randomUUID();
        String senderPhone = "+919876540003";
        String phaseKey = "conv:" + tenantId + ":" + sha256(senderPhone) + ":lead_capture_phase";
        redisTemplate.opsForValue().set(phaseKey, "CONSENT_SENT");

        eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), senderPhone,
                "no thanks", "PH_NUM", Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            String phase = redisTemplate.opsForValue().get(phaseKey);
            assertThat(phase).isEqualTo("DECLINED");
        });

        // Give async listener time, then verify no lead
        await().atMost(2, SECONDS).untilAsserted(() -> {
            var leads = leadRepository.findAll().stream()
                    .filter(l -> l.getTenantId().equals(tenantId))
                    .toList();
            assertThat(leads).isEmpty();
        });
    }
}
