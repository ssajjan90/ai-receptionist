package com.aireceptionist.whatsapp.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class WhatsAppQueueService {

    static final String QUEUE_KEY_PREFIX = "queue:whatsapp:";

    private final StringRedisTemplate stringRedisTemplate;

    public WhatsAppQueueService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void enqueueMessage(String tenantId, String recipientPhone, String message) {
        String value = "PHONE:" + recipientPhone + "|MSG:" + message;
        stringRedisTemplate.opsForZSet().add(queueKey(tenantId), value, Instant.now().toEpochMilli());
    }

    public List<String> dequeueMessages(String tenantId, int maxCount) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().popMin(queueKey(tenantId), maxCount);
        if (tuples == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null) {
                messages.add(tuple.getValue());
            }
        }
        return messages;
    }

    public long getPendingCount(String tenantId) {
        Long count = stringRedisTemplate.opsForZSet().zCard(queueKey(tenantId));
        return count == null ? 0 : count;
    }

    public static QueuedMessage parse(String rawValue) {
        int idx = rawValue.indexOf("|MSG:");
        if (idx < 0 || !rawValue.startsWith("PHONE:")) {
            return new QueuedMessage("", rawValue);
        }
        String phone = rawValue.substring("PHONE:".length(), idx);
        String message = rawValue.substring(idx + "|MSG:".length());
        return new QueuedMessage(phone, message);
    }

    private String queueKey(String tenantId) {
        return QUEUE_KEY_PREFIX + tenantId;
    }

    public record QueuedMessage(String recipientPhone, String message) {}
}
