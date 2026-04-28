package com.aireceptionist.common.util;

public final class SanitizationUtil {

    private static final String SCRIPT_PATTERN = "(?is)<script.*?>.*?</script>";
    private static final String IFRAME_PATTERN = "(?is)<iframe.*?>.*?</iframe>";
    private static final String HTML_TAG_PATTERN = "(?is)<[^>]+>";

    private SanitizationUtil() {
    }

    public static String stripHtml(String input) {
        if (input == null) {
            return null;
        }

        return input
                .replaceAll(SCRIPT_PATTERN, "")
                .replaceAll(IFRAME_PATTERN, "")
                .replaceAll(HTML_TAG_PATTERN, "")
                .trim();
    }
}
