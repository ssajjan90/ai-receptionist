package com.aireceptionist.tenant.adapter.out.redis;

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
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisOtpAdapter(StringRedisTemplate redisTemplate,
                           @Value("${app.otp.secret}") String otpSecret) {
        this.redisTemplate = redisTemplate;
        this.otpSecret = otpSecret;
    }

    @Override
    public String generateAndStore(String phone) {
        String otp = "%06d".formatted(secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(key(phone), hmac(otp), OTP_TTL);
        return otp;
    }

    @Override
    public void verifyAndConsume(String phone, String otp) {
        String key = key(phone);
        String storedOtp = redisTemplate.opsForValue().get(key);
        if (storedOtp == null) {
            throw new ValidationException("OTP_EXPIRED", "OTP has expired. Request a new OTP.");
        }
        if (!storedOtp.equals(hmac(otp))) {
            throw new ValidationException("INVALID_OTP", "Invalid OTP.");
        }
        redisTemplate.delete(key);
    }

    @Override
    public void invalidateExisting(String phone) {
        redisTemplate.delete(key(phone));
    }

    private String key(String phone) {
        return "otp:" + phone;
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
