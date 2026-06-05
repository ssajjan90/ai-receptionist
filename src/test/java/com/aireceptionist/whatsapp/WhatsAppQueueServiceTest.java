package com.aireceptionist.whatsapp;

import com.aireceptionist.whatsapp.service.WhatsAppQueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WhatsAppQueueServiceTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private WhatsAppQueueService queueService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        queueService = new WhatsAppQueueService(template);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void enqueueAndDequeueMessagesInFifoOrder() {
        String tenantId = "tenant-abc";

        queueService.enqueueMessage(tenantId, "9191", "First");
        queueService.enqueueMessage(tenantId, "9192", "Second");
        queueService.enqueueMessage(tenantId, "9193", "Third");

        assertThat(queueService.getPendingCount(tenantId)).isEqualTo(3);

        List<String> dequeued = queueService.dequeueMessages(tenantId, 10);
        assertThat(dequeued).hasSize(3);

        WhatsAppQueueService.QueuedMessage first = WhatsAppQueueService.parse(dequeued.get(0));
        assertThat(first.recipientPhone()).isEqualTo("9191");
        assertThat(first.message()).isEqualTo("First");

        assertThat(queueService.getPendingCount(tenantId)).isZero();
    }

    @Test
    void dequeueRespectsMaxCount() {
        String tenantId = "tenant-limit";

        for (int i = 1; i <= 5; i++) {
            queueService.enqueueMessage(tenantId, "919" + i, "Msg" + i);
        }

        List<String> batch = queueService.dequeueMessages(tenantId, 2);
        assertThat(batch).hasSize(2);
        assertThat(queueService.getPendingCount(tenantId)).isEqualTo(3);
    }

    @Test
    void getPendingCountReturnsZeroForEmptyQueue() {
        assertThat(queueService.getPendingCount("no-such-tenant")).isZero();
    }

    @Test
    void parseExtractsPhoneAndMessage() {
        queueService.enqueueMessage("t1", "919876543210", "Hello World");
        List<String> msgs = queueService.dequeueMessages("t1", 1);
        WhatsAppQueueService.QueuedMessage parsed = WhatsAppQueueService.parse(msgs.get(0));
        assertThat(parsed.recipientPhone()).isEqualTo("919876543210");
        assertThat(parsed.message()).isEqualTo("Hello World");
    }
}
