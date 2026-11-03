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
    // Two per-tenant caches go stale when the KB changes: KnowledgeBaseService's search cache
    // (tenant:{id}:kb:*) and LlmService's AI-answer cache (tenant:{id}:query:*, 5 min TTL).
    // Only the first used to be evicted, so a repeated question kept getting the old answer for
    // up to 5 minutes on WhatsApp and voice alike — breaking NFR3's 60s propagation (found by
    // story 6.4's VoiceKbReuseTest).
    private static final List<String> QUERY_CACHE_PATTERNS = List.of("tenant:%s:kb:*", "tenant:%s:query:*");

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
        // Code review: tenantId goes into a Redis SCAN glob — a '*' or '?' (e.g. a malformed
        // channel name) would evict every tenant's caches. Only ever accept a UUID.
        try {
            java.util.UUID.fromString(tenantId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("Ignoring KB cache eviction for invalid tenant id '{}'", tenantId);
            return;
        }
        for (String patternTemplate : QUERY_CACHE_PATTERNS) {
            evictMatching(String.format(patternTemplate, tenantId), tenantId);
        }
    }

    private void evictMatching(String pattern, String tenantId) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                List<byte[]> keysToDelete = new ArrayList<>();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    cursor.forEachRemaining(keysToDelete::add);
                }
                if (!keysToDelete.isEmpty()) {
                    connection.del(keysToDelete.toArray(new byte[0][]));
                    log.debug("Evicted {} cache keys ({}) for tenant={}", keysToDelete.size(), pattern, tenantId);
                }
                return null;
            });
        } catch (Exception ex) {
            log.warn("Failed to evict query cache for tenant={}: {}", tenantId, ex.getMessage());
        }
    }
}
