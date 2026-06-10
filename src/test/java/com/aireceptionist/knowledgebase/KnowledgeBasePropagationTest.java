package com.aireceptionist.knowledgebase;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBasePropagationTest extends AbstractIntegrationTest {

    @Autowired KnowledgeBaseService knowledgeBaseService;
    @Autowired KnowledgeEntryRepository entryRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void clearData() {
        entryRepository.deleteAll();
    }

    @Test
    void addProductPublishesKbUpdateOnPubSubChannel() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedChannel = new AtomicReference<>();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        MessageListener listener = (Message message, byte[] pattern) -> {
            receivedChannel.set(new String(message.getChannel()));
            latch.countDown();
        };
        container.addMessageListener(listener, new PatternTopic("kb:update:*"));
        container.afterPropertiesSet();
        container.start();

        try {
            knowledgeBaseService.addOrUpdateProduct(tenantId, "Test Widget", "500");

            boolean received = latch.await(2, TimeUnit.SECONDS);
            assertThat(received).as("kb:update pub/sub event must be received within 2 seconds").isTrue();
            assertThat(receivedChannel.get()).isEqualTo("kb:update:" + tenantId);
        } finally {
            container.stop();
            try { container.destroy(); } catch (Exception ignored) {}
        }
    }

    @Test
    void addProductEvictsQueryCacheKeys() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();

        // Pre-populate a fake query cache key for the tenant
        String cacheKey = "tenant:" + tenantId + ":query:somehash";
        redisTemplate.opsForValue().set(cacheKey, "cached-response");
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNotNull();

        knowledgeBaseService.addOrUpdateProduct(tenantId, "Gadget Pro", "1200");

        // Allow async cache eviction time
        Thread.sleep(500);

        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
    }

    @Test
    void deleteEntryPublishesKbUpdateAndEvictsCache() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();

        // First add a product so we can delete it
        knowledgeBaseService.addOrUpdateProduct(tenantId, "Temp Product", "999");
        assertThat(entryRepository.findByTenantIdAndProductName(tenantId, "Temp Product")).isPresent();

        // Pre-populate cache
        String cacheKey = "tenant:" + tenantId + ":query:anotherhash";
        redisTemplate.opsForValue().set(cacheKey, "stale-response");

        CountDownLatch latch = new CountDownLatch(1);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(
                (msg, p) -> latch.countDown(),
                new PatternTopic("kb:update:*"));
        container.afterPropertiesSet();
        container.start();

        try {
            boolean deleted = knowledgeBaseService.deleteEntry(tenantId, "Temp Product");
            assertThat(deleted).isTrue();

            boolean received = latch.await(2, TimeUnit.SECONDS);
            assertThat(received).as("kb:update published after delete").isTrue();
        } finally {
            container.stop();
            try { container.destroy(); } catch (Exception ignored) {}
        }

        Thread.sleep(300);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
    }
}
