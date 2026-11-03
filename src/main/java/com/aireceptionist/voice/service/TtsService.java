package com.aireceptionist.voice.service;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.google.cloud.texttospeech.v1.AudioConfig;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.cloud.texttospeech.v1.SynthesisInput;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.TextToSpeechSettings;
import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Story 6.2 (Task 3, AC4): Google Cloud Text-to-Speech. Only used when
 * {@code app.voice.tts.provider=google}; the client is created lazily on first use so local dev
 * and tests (provider {@code say}) never need Google credentials.
 *
 * <p>Output is MP3 — Exotel's {@code <Play>} accepts MP3/WAV over HTTPS; MP3 keeps the Redis
 * footprint of {@link VoiceAudioStore} small.
 *
 * <p>Each call has a hard deadline ({@code app.voice.tts.timeout-ms}, single attempt) so a slow
 * Google API can't stall an Exotel webhook — callers fall back to {@code <Say>} (code review).
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final java.time.Duration timeout;
    private volatile TextToSpeechClient client;

    public TtsService(@Value("${app.voice.tts.timeout-ms:2500}") long timeoutMs) {
        this.timeout = java.time.Duration.ofMillis(timeoutMs);
    }

    @CircuitBreaker(name = "ttsService")
    public byte[] synthesize(String text, String languageCode) {
        try {
            SynthesizeSpeechResponse response = client().synthesizeSpeech(
                    SynthesisInput.newBuilder().setText(text).build(),
                    VoiceSelectionParams.newBuilder().setLanguageCode(googleLanguage(languageCode)).build(),
                    AudioConfig.newBuilder().setAudioEncoding(AudioEncoding.MP3).build());
            return response.getAudioContent().toByteArray();
        } catch (Exception ex) {
            throw new ExternalServiceException("Google TTS synthesis failed", ex);
        }
    }

    static String googleLanguage(String languageCode) {
        return languageCode != null && languageCode.startsWith("hi") ? "hi-IN" : "en-IN";
    }

    private TextToSpeechClient client() throws java.io.IOException {
        TextToSpeechClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    TextToSpeechSettings.Builder settings = TextToSpeechSettings.newBuilder();
                    settings.synthesizeSpeechSettings().setRetrySettings(RetrySettings.newBuilder()
                            .setMaxAttempts(1)
                            .setInitialRpcTimeoutDuration(timeout)
                            .setMaxRpcTimeoutDuration(timeout)
                            .setTotalTimeoutDuration(timeout)
                            .build());
                    current = TextToSpeechClient.create(settings.build());
                    client = current;
                    log.info("Google TextToSpeechClient initialised");
                }
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        TextToSpeechClient current = client;
        if (current != null) {
            current.close();
        }
    }
}
