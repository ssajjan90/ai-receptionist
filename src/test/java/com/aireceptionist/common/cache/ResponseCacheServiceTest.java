package com.aireceptionist.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private ResponseCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new ResponseCacheService(redisTemplate);
    }

    @Test
    void get_usesCorrectKeyPattern() {
        when(valueOps.get("tenant:abc:query:xyz")).thenReturn("cached response");

        Optional<String> result = service.get("abc", "xyz");

        assertThat(result).contains("cached response");
        verify(valueOps).get("tenant:abc:query:xyz");
    }

    @Test
    void get_returnsEmptyWhenKeyMissing() {
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<String> result = service.get("abc", "xyz");

        assertThat(result).isEmpty();
    }

    @Test
    void put_usesCorrectKeyPatternAndTtl() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        service.put("abc", "xyz", "my response");

        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("tenant:abc:query:xyz");
        assertThat(valueCaptor.getValue()).isEqualTo("my response");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(30));
    }
}
