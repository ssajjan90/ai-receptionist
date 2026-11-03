package com.aireceptionist.voice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the URLs Exotel calls back / fetches. With {@code app.voice.public-base-url} set they are
 * absolute (what a real Exotel account needs); left blank (local dev, tests) they stay relative.
 *
 * <p>Fails fast at startup when Google TTS is enabled without a public HTTPS base URL — otherwise
 * every {@code <Play>} URL would point at localhost and callers would hear silence while the app
 * believes it succeeded (Epic 6 code review). Prod sets the URL with no default.
 */
@Component
public class VoiceCallbackUrls {

    public static final String TRANSCRIPT_PATH = "/webhooks/voice/transcript";
    public static final String TRANSFER_TIMEOUT_PATH = "/webhooks/voice/transfer-timeout";
    public static final String AUDIO_PATH = "/webhooks/voice/audio/";

    private final String baseUrl;

    public VoiceCallbackUrls(@Value("${app.voice.public-base-url:}") String publicBaseUrl,
                             @Value("${app.voice.tts.provider:say}") String ttsProvider) {
        String trimmed = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if ("google".equalsIgnoreCase(ttsProvider) && !isPublicHttps(baseUrl)) {
            throw new IllegalStateException("app.voice.tts.provider=google requires app.voice.public-base-url "
                    + "(VOICE_PUBLIC_BASE_URL) to be a public https:// URL Exotel can fetch audio from; got '"
                    + baseUrl + "'");
        }
    }

    public String transcript() {
        return baseUrl + TRANSCRIPT_PATH;
    }

    public String transferTimeout() {
        return baseUrl + TRANSFER_TIMEOUT_PATH;
    }

    public String audio(String audioId) {
        return baseUrl + AUDIO_PATH + audioId;
    }

    static boolean isPublicHttps(String url) {
        return url.startsWith("https://") && !url.contains("localhost") && !url.contains("127.0.0.1");
    }
}
