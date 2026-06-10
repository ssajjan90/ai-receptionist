package com.aireceptionist.ai.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);
    private static final String KEY_PREFIX = "conv:";
    private static final String LANG_SUFFIX = ":lang";
    private static final String MODE_SUFFIX = ":mode";
    private static final Duration TTL = Duration.ofSeconds(1800);
    private static final int MAX_LIST_SIZE = 200;

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
            redisTemplate.opsForList().trim(key, -MAX_LIST_SIZE, -1);
            redisTemplate.expire(key, TTL);
            redisTemplate.expire(key + MODE_SUFFIX, TTL);
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

    public void setSessionLanguage(String tenantId, String customerPhone, String languageCode) {
        String key = key(tenantId, customerPhone) + LANG_SUFFIX;
        try {
            redisTemplate.opsForValue().set(key, languageCode, TTL);
        } catch (Exception ex) {
            log.warn("Failed to store session language for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    public void setEmpathyMode(String tenantId, String customerPhone) {
        String key = key(tenantId, customerPhone) + MODE_SUFFIX;
        try {
            redisTemplate.opsForValue().set(key, "EMPATHY", TTL);
        } catch (Exception ex) {
            log.warn("Failed to store empathy mode for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    public boolean isEmpathyMode(String tenantId, String customerPhone) {
        String key = key(tenantId, customerPhone) + MODE_SUFFIX;
        try {
            return "EMPATHY".equals(redisTemplate.opsForValue().get(key));
        } catch (Exception ex) {
            log.warn("Failed to check empathy mode for tenant={}: {}", tenantId, ex.getMessage());
            return false;
        }
    }

    private String key(String tenantId, String customerPhone) {
        return KEY_PREFIX + tenantId + ":" + sha256(customerPhone);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException("SHA-256 is unavailable", ex);
        }
    }
}
