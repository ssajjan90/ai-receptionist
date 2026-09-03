package com.aireceptionist.knowledgebase;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.multitenancy.TenantContext;
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
            // KnowledgeBaseService relies entirely on the caller having already set
            // TenantContext (normally done by the JWT filter for an HTTP request, or by the
            // WhatsApp owner-command listener for an event-driven call) — knowledge_entries
            // carries RLS (V8/W99), so a direct service call in a test needs the same wrapping.
            TenantContext.setCurrentTenant(tenantId.toString());
            try {
                knowledgeBaseService.addOrUpdateProduct(tenantId, "Test Widget", "500");
            } finally {
                TenantContext.clear();
            }

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

        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            knowledgeBaseService.addOrUpdateProduct(tenantId, "Gadget Pro", "1200");
        } finally {
            TenantContext.clear();
        }

        // Allow async cache eviction time
        Thread.sleep(500);

        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
    }

    @Test
    void deleteEntryPublishesKbUpdateAndEvictsCache() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();

        // First add a product so we can delete it
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            knowledgeBaseService.addOrUpdateProduct(tenantId, "Temp Product", "999");
            assertThat(entryRepository.findByTenantIdAndProductName(tenantId, "Temp Product")).isPresent();
        } finally {
            TenantContext.clear();
        }

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
            TenantContext.setCurrentTenant(tenantId.toString());
            boolean deleted;
            try {
                deleted = knowledgeBaseService.deleteEntry(tenantId, "Temp Product");
            } finally {
                TenantContext.clear();
            }
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
