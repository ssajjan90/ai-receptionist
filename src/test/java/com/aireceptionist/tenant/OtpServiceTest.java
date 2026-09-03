package com.aireceptionist.tenant;

import com.aireceptionist.common.exception.RateLimitExceededException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.tenant.adapter.out.redis.RedisOtpAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedisOtpAdapter otpService = new RedisOtpAdapter(redisTemplate, "test-secret", 5, 60);

    @Test
    void generateAndStoreStoresSixDigitOtpWithFiveMinuteTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.eq("otp:resend-cooldown:+919876543210"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);

        String otp = otpService.generateAndStore("+919876543210");

        assertThat(otp).matches("\\d{6}");
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                org.mockito.ArgumentMatchers.argThat(stored -> !stored.equals(otp) && stored.length() == 64),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
    }

    @Test
    void generateAndStoreRejectsResendWithinCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.eq("otp:resend-cooldown:+919876543210"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> otpService.generateAndStore("+919876543210"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting("errorCode")
                .isEqualTo("OTP_RESEND_TOO_SOON");
    }

    @Test
    void verifyAndConsumeDeletesOtpOnMatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.eq("otp:resend-cooldown:+919876543210"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        String otp = otpService.generateAndStore("+919876543210");
        var storedCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                storedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        when(valueOperations.get("otp:+919876543210")).thenReturn(storedCaptor.getValue());
        when(valueOperations.increment("otp:attempts:+919876543210")).thenReturn(1L);

        otpService.verifyAndConsume("+919876543210", otp);

        verify(redisTemplate).delete("otp:+919876543210");
        // generateAndStore already deletes it once (fresh-OTP attempt reset); verifyAndConsume's
        // success path deletes it again.
        verify(redisTemplate, org.mockito.Mockito.times(2)).delete("otp:attempts:+919876543210");
    }

    @Test
    void verifyAndConsumeThrowsInvalidOtpOnMismatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.eq("otp:resend-cooldown:+919876543210"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        otpService.generateAndStore("+919876543210");
        var storedCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                storedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        when(valueOperations.get("otp:+919876543210")).thenReturn(storedCaptor.getValue());
        when(valueOperations.increment("otp:attempts:+919876543210")).thenReturn(1L);

        assertThatThrownBy(() -> otpService.verifyAndConsume("+919876543210", "000000"))
                .isInstanceOf(ValidationException.class)
                .extracting("errorCode")
                .isEqualTo("INVALID_OTP");
    }

    @Test
    void verifyAndConsumeThrowsExpiredOtpWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:+919876543210")).thenReturn(null);
        when(valueOperations.increment("otp:attempts:+919876543210")).thenReturn(1L);

        assertThatThrownBy(() -> otpService.verifyAndConsume("+919876543210", "123456"))
                .isInstanceOf(ValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OTP_EXPIRED");
    }

    @Test
    void verifyAndConsumeLocksOutAfterMaxAttemptsExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("otp:attempts:+919876543210")).thenReturn(6L);

        assertThatThrownBy(() -> otpService.verifyAndConsume("+919876543210", "000000"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting("errorCode")
                .isEqualTo("OTP_LOCKED");

        verify(redisTemplate).delete("otp:+919876543210");
        verify(valueOperations, org.mockito.Mockito.never()).get("otp:+919876543210");
    }

    @Test
    void invalidateExistingDeletesCurrentOtpAndAttempts() {
        otpService.invalidateExisting("+919876543210");

        verify(redisTemplate).delete("otp:+919876543210");
        verify(redisTemplate).delete("otp:attempts:+919876543210");
    }
}
