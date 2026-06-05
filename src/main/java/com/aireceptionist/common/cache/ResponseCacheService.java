package com.aireceptionist.common.cache;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ResponseCacheService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public ResponseCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String tenantId, String queryHash) {
        Object value = redisTemplate.opsForValue().get(cacheKey(tenantId, queryHash));
        return Optional.ofNullable(value).map(Object::toString);
    }

    public void put(String tenantId, String queryHash, String response) {
        redisTemplate.opsForValue().set(cacheKey(tenantId, queryHash), response, CACHE_TTL);
    }

    public void evict(String tenantId) {
        String pattern = "tenant:" + tenantId + ":query:*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            List<byte[]> keysToDelete = new ArrayList<>();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                cursor.forEachRemaining(keysToDelete::add);
            }
            if (!keysToDelete.isEmpty()) {
                connection.del(keysToDelete.toArray(new byte[0][]));
            }
            return null;
        });
    }

    private String cacheKey(String tenantId, String queryHash) {
        return "tenant:" + tenantId + ":query:" + queryHash;
    }
}
