package com.aireceptionist.ai.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);
    private static final String KEY_PREFIX = "conv:";
    private static final Duration TTL = Duration.ofSeconds(1800);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationContextService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void addTurn(String tenantId, String customerPhone, String role, String content) {
        String key = key(tenantId, customerPhone);
        try {
            String json = objectMapper.writeValueAsString(new ConversationTurn(role, content));
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, TTL);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize conversation turn for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    public List<ConversationTurn> getHistory(String tenantId, String customerPhone, int maxTurns) {
        String key = key(tenantId, customerPhone);
        List<String> entries = redisTemplate.opsForList().range(key, -(long) maxTurns * 2, -1);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ConversationTurn.class);
                    } catch (Exception ex) {
                        log.warn("Failed to deserialize conversation turn: {}", ex.getMessage());
                        return null;
                    }
                })
                .filter(t -> t != null)
                .toList();
    }

    private String key(String tenantId, String customerPhone) {
        return KEY_PREFIX + tenantId + ":" + customerPhone;
    }
}
