package com.aireceptionist.common.otp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class OtpService {

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final String OTP_KEY_PREFIX = "otp:";

    private final StringRedisTemplate stringRedisTemplate;
    private final String otpSecret;

    public OtpService(StringRedisTemplate stringRedisTemplate,
                      @Value("${app.otp.secret}") String otpSecret) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.otpSecret = otpSecret;
    }

    public void setOtp(String namespace, String phone, String otp) {
        stringRedisTemplate.opsForValue().set(otpKey(namespace, phone), hmac(otp), OTP_TTL);
    }

    public boolean verifyOtp(String namespace, String phone, String plainOtp) {
        String stored = stringRedisTemplate.opsForValue().get(otpKey(namespace, phone));
        return stored != null && stored.equals(hmac(plainOtp));
    }

    public void deleteOtp(String namespace, String phone) {
        stringRedisTemplate.delete(otpKey(namespace, phone));
    }

    private String otpKey(String namespace, String phone) {
        return OTP_KEY_PREFIX + namespace + ":" + phone;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
