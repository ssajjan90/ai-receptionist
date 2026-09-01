package com.aireceptionist.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared SHA-256 hex digest — extracted (code review of story 5.3, 2026-09-01) from what had
 * become duplicated, byte-for-byte identical private copies in {@code Lead} and
 * {@code AdminService}. A future change (e.g. phone-number normalization before hashing) made in
 * only one place would otherwise silently break the two call sites matching each other.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }
}
