package com.aireceptionist.knowledgebase.service;

import com.aireceptionist.knowledgebase.event.KnowledgeEntryDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeCacheService implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCacheService.class);
    private static final String QUERY_CACHE_PATTERN = "tenant:%s:kb:*";

    private final StringRedisTemplate redisTemplate;

    public KnowledgeCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        // channel = "kb:update:{tenantId}"
        String tenantId = channel.replace("kb:update:", "");
        log.debug("KB update event received for tenant={}, invalidating query cache", tenantId);
        evictQueryCache(tenantId);
    }

    @ApplicationModuleListener
    void onKnowledgeEntryDeleted(KnowledgeEntryDeletedEvent event) {
        log.debug("KB entry deleted for tenant={}, invalidating query cache", event.getTenantId());
        evictQueryCache(event.getTenantId());
    }

    private void evictQueryCache(String tenantId) {
        String pattern = String.format(QUERY_CACHE_PATTERN, tenantId);
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                List<byte[]> keysToDelete = new ArrayList<>();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    cursor.forEachRemaining(keysToDelete::add);
                }
                if (!keysToDelete.isEmpty()) {
                    connection.del(keysToDelete.toArray(new byte[0][]));
                    log.debug("Evicted {} query cache keys for tenant={}", keysToDelete.size(), tenantId);
                }
                return null;
            });
        } catch (Exception ex) {
            log.warn("Failed to evict query cache for tenant={}: {}", tenantId, ex.getMessage());
        }
    }
}
