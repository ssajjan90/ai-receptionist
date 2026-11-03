package com.aireceptionist.voice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 6.2: short-lived holding area for synthesized audio so Exotel can fetch it by URL
 * ({@code <Play>} needs an HTTP URL; a data URI isn't supported). Kept in Redis with a TTL, never
 * in Postgres — story 6.4 AC3 requires that {@code voice_calls} hold no raw audio.
 */
@Component
public class VoiceAudioStore {

    static final String KEY_PREFIX = "voice:audio:";

    private final StringRedisTemplate redisTemplate;

    public VoiceAudioStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String store(byte[] audio, Duration ttl) {
        String id = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + id, Base64.getEncoder().encodeToString(audio), ttl);
        return id;
    }

    public Optional<byte[]> load(String id) {
        if (!isValidId(id)) {
            return Optional.empty();
        }
        String encoded = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        return encoded == null ? Optional.empty() : Optional.of(Base64.getDecoder().decode(encoded));
    }

    public boolean exists(String id) {
        return isValidId(id) && Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + id));
    }

    public void delete(String id) {
        if (isValidId(id)) {
            redisTemplate.delete(KEY_PREFIX + id);
        }
    }

    private boolean isValidId(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }
}
