package com.aireceptionist.common.otp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private static final String TEST_SECRET = "test-pepper";

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(stringRedisTemplate, TEST_SECRET);
    }

    @Test
    void setOtp_storesHmacHashWithNamespacedKeyAndFiveMinuteTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        otpService.setOtp("reg", "+919000000001", "123456");

        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("otp:reg:+919000000001");
        assertThat(valueCaptor.getValue()).isNotEqualTo("123456"); // stored as HMAC hash, not plaintext
        assertThat(valueCaptor.getValue()).hasSize(64);            // HmacSHA256 = 32 bytes = 64 hex chars
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void verifyOtp_returnsTrueForMatchingOtp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);

        otpService.setOtp("reg", "+919000000001", "654321");
        verify(valueOps).set(eq("otp:reg:+919000000001"), hashCaptor.capture(), any());
        when(valueOps.get("otp:reg:+919000000001")).thenReturn(hashCaptor.getValue());

        assertThat(otpService.verifyOtp("reg", "+919000000001", "654321")).isTrue();
    }

    @Test
    void verifyOtp_returnsFalseWhenNoOtpStored() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("otp:reg:+919000000001")).thenReturn(null);

        assertThat(otpService.verifyOtp("reg", "+919000000001", "123456")).isFalse();
    }

    @Test
    void verifyOtp_returnsFalseForWrongOtp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);

        otpService.setOtp("reg", "+919000000001", "123456");
        verify(valueOps).set(eq("otp:reg:+919000000001"), hashCaptor.capture(), any());
        when(valueOps.get("otp:reg:+919000000001")).thenReturn(hashCaptor.getValue());

        assertThat(otpService.verifyOtp("reg", "+919000000001", "654321")).isFalse();
    }

    @Test
    void deleteOtp_usesNamespacedKey() {
        otpService.deleteOtp("reg", "+919000000001");

        verify(stringRedisTemplate).delete("otp:reg:+919000000001");
    }
}
