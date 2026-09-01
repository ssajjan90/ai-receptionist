package com.aireceptionist.knowledgebase;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.event.UnansweredQueryFlaggedEvent;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

class SelfImprovingKbTest extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired KnowledgeEntryRepository entryRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        entryRepository.deleteAll();
    }

    @Test
    void ownerReplyToUnansweredQueryAddsKbEntryAndResolvesAuditLog() {
        UUID tenantId = UUID.randomUUID();
        UUID auditLogId = UUID.randomUUID();
        String ownerPhone = "+911234567890";
        String question = "What is your return policy?";
        String answer = "30 days return with original receipt.";

        auditLogRepository.save(new AuditLogEntry(
                auditLogId,
                tenantId,
                "AUDIT_LOW_CONFIDENCE",
                BigDecimal.valueOf(0.30),
                "somehash",
                Instant.now()
        ));

        eventPublisher.publishEvent(
                new UnansweredQueryFlaggedEvent(tenantId.toString(), "+919876543210", question, ownerPhone, auditLogId));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            String key = "train:" + tenantId + ":" + ownerPhone;
            String json = redisTemplate.opsForValue().get(key);
            assertThat(json).isNotNull().contains("return policy").contains(auditLogId.toString());
        });

        eventPublisher.publishEvent(
                new OwnerCommandReceivedEvent(tenantId.toString(), answer, ownerPhone, Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var entries = entryRepository.findByTenantId(tenantId).stream()
                    .filter(e -> "OWNER_TRAINED".equals(e.getSource()))
                    .toList();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getQuestion()).isEqualTo(question);
            assertThat(entries.get(0).getAnswer()).isEqualTo(answer);
        });

        await().atMost(5, SECONDS).untilAsserted(() -> {
            Boolean resolved = jdbcTemplate.queryForObject(
                    "SELECT resolved FROM audit_log WHERE id = ?", Boolean.class, auditLogId);
            assertThat(resolved).isTrue();
        });

        String trainKey = "train:" + tenantId + ":" + ownerPhone;
        assertThat(redisTemplate.opsForValue().get(trainKey)).isNull();
    }

    @Test
    void ownerSkipDoesNotAddKbEntryAndClearsKey() {
        UUID tenantId = UUID.randomUUID();
        UUID auditLogId = UUID.randomUUID();
        String ownerPhone = "+911111111111";
        String question = "Do you have student discounts?";

        auditLogRepository.save(new AuditLogEntry(
                auditLogId,
                tenantId,
                "AUDIT_LOW_CONFIDENCE",
                BigDecimal.valueOf(0.25),
                "anotherhash",
                Instant.now()
        ));

        eventPublisher.publishEvent(
                new UnansweredQueryFlaggedEvent(tenantId.toString(), "+919999999999", question, ownerPhone, auditLogId));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            String key = "train:" + tenantId + ":" + ownerPhone;
            assertThat(redisTemplate.opsForValue().get(key)).isNotNull();
        });

        eventPublisher.publishEvent(
                new OwnerCommandReceivedEvent(tenantId.toString(), "SKIP", ownerPhone, Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            String key = "train:" + tenantId + ":" + ownerPhone;
            assertThat(redisTemplate.opsForValue().get(key)).isNull();
        });

        var entries = entryRepository.findByTenantId(tenantId).stream()
                .filter(e -> "OWNER_TRAINED".equals(e.getSource()))
                .toList();
        assertThat(entries).isEmpty();
    }

    @Test
    void propagationEventPublishedAfterTraining() {
        UUID tenantId = UUID.randomUUID();
        UUID auditLogId = UUID.randomUUID();
        String ownerPhone = "+912222222222";
        String question = "What are your business hours?";
        String answer = "We are open 9am to 9pm Monday to Saturday.";

        auditLogRepository.save(new AuditLogEntry(
                auditLogId,
                tenantId,
                "AUDIT_LOW_CONFIDENCE",
                BigDecimal.valueOf(0.20),
                "hash3",
                Instant.now()
        ));

        eventPublisher.publishEvent(
                new UnansweredQueryFlaggedEvent(tenantId.toString(), "+918888888888", question, ownerPhone, auditLogId));

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(redisTemplate.opsForValue().get("train:" + tenantId + ":" + ownerPhone)).isNotNull());

        eventPublisher.publishEvent(
                new OwnerCommandReceivedEvent(tenantId.toString(), answer, ownerPhone, Instant.now()));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var entries = entryRepository.findByTenantId(tenantId).stream()
                    .filter(e -> "OWNER_TRAINED".equals(e.getSource()))
                    .toList();
            assertThat(entries).hasSize(1);
        });

        await().atMost(5, SECONDS).untilAsserted(() -> {
            Boolean resolved = jdbcTemplate.queryForObject(
                    "SELECT resolved FROM audit_log WHERE id = ?", Boolean.class, auditLogId);
            assertThat(resolved).isTrue();
        });
    }
}
