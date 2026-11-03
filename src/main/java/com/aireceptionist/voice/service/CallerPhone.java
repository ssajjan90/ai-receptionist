package com.aireceptionist.voice.service;

import java.util.regex.Pattern;

/**
 * Epic 6 code review: Exotel may send the caller as national format (0XXXXXXXXXX), bare
 * 10 digits, 91XXXXXXXXXX, blank, or "anonymous". Normalise Indian numbers to +91XXXXXXXXXX so
 * they match WhatsApp/lead records, and tell callers apart from unusable IDs (which must not
 * share a conversation context or be sent a WhatsApp follow-up).
 */
public final class CallerPhone {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");
    private static final Pattern USABLE = Pattern.compile("^\\+\\d{8,15}$");

    private CallerPhone() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (s.matches("^0\\d{10}$")) {
            return "+91" + s.substring(1);
        }
        if (s.matches("^[6-9]\\d{9}$")) {
            return "+91" + s;
        }
        if (s.matches("^91\\d{10}$")) {
            return "+" + s;
        }
        if (s.matches("^00\\d{8,15}$")) {
            return "+" + s.substring(2);
        }
        return s;
    }

    /** True if this is a real phone number we can key a conversation on and message. */
    public static boolean isUsable(String normalized) {
        return normalized != null && USABLE.matcher(normalized).matches();
    }
}
