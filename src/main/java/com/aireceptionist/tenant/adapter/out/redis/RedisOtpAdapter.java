package com.aireceptionist.tenant.adapter.out.redis;

import com.aireceptionist.common.exception.RateLimitExceededException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.tenant.port.out.OtpPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class RedisOtpAdapter implements OtpPort {

    static final Duration OTP_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final String otpSecret;
    private final int maxVerifyAttempts;
    private final Duration resendCooldown;
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisOtpAdapter(StringRedisTemplate redisTemplate,
                           @Value("${app.otp.secret}") String otpSecret,
                           @Value("${app.otp.max-verify-attempts:5}") int maxVerifyAttempts,
                           @Value("${app.otp.resend-cooldown-seconds:60}") long resendCooldownSeconds) {
        this.redisTemplate = redisTemplate;
        this.otpSecret = otpSecret;
        this.maxVerifyAttempts = maxVerifyAttempts;
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
    }

    @Override
    public String generateAndStore(String phone) {
        // setIfAbsent (SET NX) makes the cooldown check-and-set atomic, so two concurrent
        // resend requests can't both slip through before either one's key is visible (W20).
        Boolean acquiredCooldown = redisTemplate.opsForValue()
                .setIfAbsent(resendCooldownKey(phone), "1", resendCooldown);
        if (Boolean.FALSE.equals(acquiredCooldown)) {
            throw new RateLimitExceededException("OTP_RESEND_TOO_SOON",
                    "Please wait before requesting another OTP.");
        }
        String otp = "%06d".formatted(secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(key(phone), hmac(otp), OTP_TTL);
        redisTemplate.delete(attemptsKey(phone));
        return otp;
    }

    @Override
    public void verifyAndConsume(String phone, String otp) {
        String key = key(phone);
        String attemptsKey = attemptsKey(phone);

        // Brute-force lockout (W20): a 6-digit OTP is a 1,000,000-value space, small enough to
        // guess within the 5-minute TTL without a cap on attempts. Count every verify call
        // (success or failure) so a fresh OTP (which resets this counter) is required past the
        // limit, rather than letting a caller retry indefinitely against the same OTP.
        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(attemptsKey, OTP_TTL);
        }
        if (attempts != null && attempts > maxVerifyAttempts) {
            redisTemplate.delete(key);
            throw new RateLimitExceededException("OTP_LOCKED",
                    "Too many incorrect OTP attempts. Request a new OTP.");
        }

        String storedOtp = redisTemplate.opsForValue().get(key);
        if (storedOtp == null) {
            throw new ValidationException("OTP_EXPIRED", "OTP has expired. Request a new OTP.");
        }
        if (!storedOtp.equals(hmac(otp))) {
            throw new ValidationException("INVALID_OTP", "Invalid OTP.");
        }
        redisTemplate.delete(key);
        redisTemplate.delete(attemptsKey);
    }

    @Override
    public void invalidateExisting(String phone) {
        redisTemplate.delete(key(phone));
        redisTemplate.delete(attemptsKey(phone));
    }

    private String key(String phone) {
        return "otp:" + phone;
    }

    private String attemptsKey(String phone) {
        return "otp:attempts:" + phone;
    }

    private String resendCooldownKey(String phone) {
        return "otp:resend-cooldown:" + phone;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
