package com.aireceptionist.voice.service;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.tenant.event.TenantOnboardedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Story 6.2 (AC1, AC4): turns text into the ExoML "speak this" verb.
 *
 * <ul>
 *   <li>{@code provider=say} (default; local dev/tests): Exotel's built-in {@code <Say>} — no
 *       external TTS call, so there is nothing to pre-generate.</li>
 *   <li>{@code provider=google}: audio from {@link TtsService}, held in {@link VoiceAudioStore}
 *       and returned as {@code <Play>url</Play>}. The greeting is pre-generated (on
 *       {@link TenantOnboardedEvent}) and cached per tenant so the initial webhook response never
 *       waits on TTS — NFR2's 1.5s budget.</li>
 * </ul>
 *
 * <p>Greeting cache value is {@code sha256(businessName)|audioId}: a renamed business misses the
 * cache and regenerates even if the onboarding event was missed. Any TTS failure degrades to
 * {@code <Say>} rather than dropping the call.
 */
@Service
public class VoiceResponseGenerator {

    private static final Logger log = LoggerFactory.getLogger(VoiceResponseGenerator.class);

    static final String GREETING_KEY_PREFIX = "voice:greeting:";
    static final String GREETING_TEMPLATE = "Hello! Welcome to %s. How can I help you today?";
    private static final String DEFAULT_BUSINESS_NAME = "our business";

    private final TtsService ttsService;
    private final VoiceAudioStore audioStore;
    private final StringRedisTemplate redisTemplate;
    private final TenantNamePort tenantNamePort;
    private final boolean useGoogleTts;
    private final String publicBaseUrl;
    private final Duration greetingTtl;
    private final Duration responseTtl;

    public VoiceResponseGenerator(TtsService ttsService,
                                  VoiceAudioStore audioStore,
                                  StringRedisTemplate redisTemplate,
                                  TenantNamePort tenantNamePort,
                                  @Value("${app.voice.tts.provider:say}") String provider,
                                  @Value("${app.voice.public-base-url:http://localhost:8080}") String publicBaseUrl,
                                  @Value("${app.voice.tts.greeting-ttl-days:30}") long greetingTtlDays,
                                  @Value("${app.voice.tts.response-ttl-minutes:10}") long responseTtlMinutes) {
        this.ttsService = ttsService;
        this.audioStore = audioStore;
        this.redisTemplate = redisTemplate;
        this.tenantNamePort = tenantNamePort;
        this.useGoogleTts = "google".equalsIgnoreCase(provider);
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.greetingTtl = Duration.ofDays(greetingTtlDays);
        this.responseTtl = Duration.ofMinutes(responseTtlMinutes);
    }

    /** AC1: the ExoML verb that plays this tenant's greeting (cached audio when using Google TTS). */
    public String getGreetingAudio(UUID tenantId) {
        String businessName = tenantNamePort.getBusinessName(tenantId.toString())
                .filter(name -> !name.isBlank())
                .orElse(DEFAULT_BUSINESS_NAME);
        String text = greetingText(businessName);
        if (!useGoogleTts) {
            return ExoMl.say(text, "en");
        }

        String key = GREETING_KEY_PREFIX + tenantId;
        String nameHash = sha256(businessName);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            String[] parts = cached.split("\\|", 2);
            if (parts.length == 2 && parts[0].equals(nameHash) && audioStore.exists(parts[1])) {
                return ExoMl.play(audioUrl(parts[1]));
            }
        }
        return generateGreeting(tenantId, businessName)
                .map(id -> ExoMl.play(audioUrl(id)))
                .orElseGet(() -> ExoMl.say(text, "en"));
    }

    /** AC4: speak an AI response. Synthesized per turn (not cached — answers are conversation-specific). */
    public String speak(String text, String languageCode) {
        if (!useGoogleTts) {
            return ExoMl.say(text, languageCode);
        }
        try {
            String id = audioStore.store(ttsService.synthesize(text, languageCode), responseTtl);
            return ExoMl.play(audioUrl(id));
        } catch (Exception ex) {
            log.warn("TTS synthesis failed, falling back to <Say>: {}", ex.getMessage());
            return ExoMl.say(text, languageCode);
        }
    }

    /** Pre-generates the greeting when a tenant goes LIVE (and on re-onboarding with a new name). */
    @ApplicationModuleListener
    void onTenantOnboarded(TenantOnboardedEvent event) {
        if (!useGoogleTts) {
            return;
        }
        String key = GREETING_KEY_PREFIX + event.getTenantIdValue();
        String previous = redisTemplate.opsForValue().get(key);
        String businessName = event.getBusinessName() == null || event.getBusinessName().isBlank()
                ? DEFAULT_BUSINESS_NAME : event.getBusinessName();
        generateGreeting(event.getTenantIdValue(), businessName);
        if (previous != null) {
            String[] parts = previous.split("\\|", 2);
            if (parts.length == 2) {
                audioStore.delete(parts[1]);
            }
        }
    }

    private java.util.Optional<String> generateGreeting(UUID tenantId, String businessName) {
        try {
            byte[] audio = ttsService.synthesize(greetingText(businessName), "en");
            String id = audioStore.store(audio, greetingTtl);
            redisTemplate.opsForValue().set(GREETING_KEY_PREFIX + tenantId, sha256(businessName) + "|" + id, greetingTtl);
            log.info("Generated voice greeting for tenant={}", tenantId);
            return java.util.Optional.of(id);
        } catch (Exception ex) {
            log.warn("Greeting TTS generation failed for tenant={}: {}", tenantId, ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    static String greetingText(String businessName) {
        return String.format(GREETING_TEMPLATE, businessName);
    }

    private String audioUrl(String audioId) {
        return publicBaseUrl + "/webhooks/voice/audio/" + audioId;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
