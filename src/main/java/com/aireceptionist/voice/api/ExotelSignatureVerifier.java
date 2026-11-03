package com.aireceptionist.voice.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Exotel webhook HMAC check, extracted from story 6.1's controller so the 6.2/6.3 callbacks
 * (transcript, transfer-timeout) share it. Signature = hex(HMAC-SHA1(sharedSecret, concatenation
 * of the given fields in order)). As 6.1 documented, the canonicalisation is this codebase's
 * assumption, not verified against Exotel's docs — revisit before connecting a live account.
 */
@Component
class ExotelSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(ExotelSignatureVerifier.class);

    private final String sharedSecret;

    ExotelSignatureVerifier(@Value("${app.exotel.shared-secret}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    boolean verify(String signature, String... fields) {
        if (signature == null || signature.isBlank() || sharedSecret == null || sharedSecret.isBlank()) {
            return false;
        }
        try {
            StringBuilder canonical = new StringBuilder();
            for (String field : fields) {
                canonical.append(field == null ? "" : field);
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String computed = HexFormat.of().formatHex(mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("Unable to verify Exotel webhook signature: {}", ex.getMessage());
            return false;
        }
    }
}
