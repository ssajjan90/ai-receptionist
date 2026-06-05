package com.aireceptionist.tenant;

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
    private final RedisOtpAdapter otpService = new RedisOtpAdapter(redisTemplate, "test-secret");

    @Test
    void generateAndStoreStoresSixDigitOtpWithFiveMinuteTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String otp = otpService.generateAndStore("+919876543210");

        assertThat(otp).matches("\\d{6}");
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                org.mockito.ArgumentMatchers.argThat(stored -> !stored.equals(otp) && stored.length() == 64),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
    }

    @Test
    void verifyAndConsumeDeletesOtpOnMatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String otp = otpService.generateAndStore("+919876543210");
        var storedCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                storedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        when(valueOperations.get("otp:+919876543210")).thenReturn(storedCaptor.getValue());

        otpService.verifyAndConsume("+919876543210", otp);

        verify(redisTemplate).delete("otp:+919876543210");
    }

    @Test
    void verifyAndConsumeThrowsInvalidOtpOnMismatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        otpService.generateAndStore("+919876543210");
        var storedCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("otp:+919876543210"),
                storedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        when(valueOperations.get("otp:+919876543210")).thenReturn(storedCaptor.getValue());

        assertThatThrownBy(() -> otpService.verifyAndConsume("+919876543210", "000000"))
                .isInstanceOf(ValidationException.class)
                .extracting("errorCode")
                .isEqualTo("INVALID_OTP");
    }

    @Test
    void verifyAndConsumeThrowsExpiredOtpWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:+919876543210")).thenReturn(null);

        assertThatThrownBy(() -> otpService.verifyAndConsume("+919876543210", "123456"))
                .isInstanceOf(ValidationException.class)
                .extracting("errorCode")
                .isEqualTo("OTP_EXPIRED");
    }

    @Test
    void invalidateExistingDeletesCurrentOtp() {
        otpService.invalidateExisting("+919876543210");

        verify(redisTemplate).delete("otp:+919876543210");
    }
}
