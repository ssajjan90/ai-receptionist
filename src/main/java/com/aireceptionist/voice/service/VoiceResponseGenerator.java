package com.aireceptionist.voice.service;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.tenant.event.TenantOnboardedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Story 6.2 (AC1, AC4): turns text into the ExoML "speak this" verb.
 *
 * <ul>
 *   <li>{@code provider=say} (default; local dev/tests): Exotel's built-in {@code <Say>} — no
 *       external TTS call, so there is nothing to pre-generate.</li>
 *   <li>{@code provider=google}: audio from {@link TtsService}, held in {@link VoiceAudioStore}
 *       and returned as {@code <Play>url</Play>}. The greeting is pre-generated (on
 *       {@link TenantOnboardedEvent}) and cached per tenant.</li>
 * </ul>
 *
 * <p>The incoming-call path never waits on TTS (NFR2's 1.5s): on a greeting cache miss (tenant
 * onboarded before this feature, renamed business, expired audio, Redis trouble) the caller hears
 * {@code <Say>} and the audio is regenerated in the background under a per-tenant lock so
 * concurrent calls don't each synthesize (code review). Cache value is
 * {@code sha256(businessName)|audioId}, so a renamed business misses and regenerates.
 */
@Service
public class VoiceResponseGenerator {

    private static final Logger log = LoggerFactory.getLogger(VoiceResponseGenerator.class);

    static final String GREETING_KEY_PREFIX = "voice:greeting:";
    static final String GREETING_LOCK_PREFIX = "voice:greeting:lock:";
    static final String GREETING_TEMPLATE = "Hello! Welcome to %s. How can I help you today?";
    private static final String DEFAULT_BUSINESS_NAME = "our business";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final TtsService ttsService;
    private final VoiceAudioStore audioStore;
    private final StringRedisTemplate redisTemplate;
    private final TenantNamePort tenantNamePort;
    private final VoiceCallbackUrls urls;
    private final Executor executor;
    private final boolean useGoogleTts;
    private final Duration greetingTtl;
    private final Duration responseTtl;

    public VoiceResponseGenerator(TtsService ttsService,
                                  VoiceAudioStore audioStore,
                                  StringRedisTemplate redisTemplate,
                                  TenantNamePort tenantNamePort,
                                  VoiceCallbackUrls urls,
                                  @Qualifier("taskExecutor") Executor executor,
                                  @Value("${app.voice.tts.provider:say}") String provider,
                                  @Value("${app.voice.tts.greeting-ttl-days:30}") long greetingTtlDays,
                                  @Value("${app.voice.tts.response-ttl-minutes:10}") long responseTtlMinutes) {
        this.ttsService = ttsService;
        this.audioStore = audioStore;
        this.redisTemplate = redisTemplate;
        this.tenantNamePort = tenantNamePort;
        this.urls = urls;
        this.executor = executor;
        this.useGoogleTts = "google".equalsIgnoreCase(provider);
        this.greetingTtl = Duration.ofDays(greetingTtlDays);
        this.responseTtl = Duration.ofMinutes(responseTtlMinutes);
    }

    /** AC1: the ExoML verb that plays this tenant's greeting (cached audio when using Google TTS). */
    public String getGreetingAudio(UUID tenantId) {
        String businessName = businessName(tenantId);
        String text = greetingText(businessName);
        if (!useGoogleTts) {
            return ExoMl.say(text, "en");
        }
        try {
            String cached = redisTemplate.opsForValue().get(GREETING_KEY_PREFIX + tenantId);
            if (cached != null) {
                String[] parts = cached.split("\\|", 2);
                if (parts.length == 2 && parts[0].equals(sha256(businessName)) && audioStore.exists(parts[1])) {
                    return ExoMl.play(urls.audio(parts[1]));
                }
            }
            regenerateInBackground(tenantId, businessName);
        } catch (Exception ex) {
            log.warn("Greeting cache unavailable for tenant={}, using <Say>: {}", tenantId, ex.getMessage());
        }
        return ExoMl.say(text, "en");
    }

    /** AC4: speak an AI response. Synthesized per turn (bounded by TtsService's deadline). */
    public String speak(String text, String languageCode) {
        if (!useGoogleTts) {
            return ExoMl.say(text, languageCode);
        }
        try {
            String id = audioStore.store(ttsService.synthesize(text, languageCode), responseTtl);
            return ExoMl.play(urls.audio(id));
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
        String businessName = event.getBusinessName() == null || event.getBusinessName().isBlank()
                ? DEFAULT_BUSINESS_NAME : event.getBusinessName();
        String previous = null;
        try {
            previous = redisTemplate.opsForValue().get(GREETING_KEY_PREFIX + event.getTenantIdValue());
        } catch (Exception ex) {
            log.warn("Could not read previous greeting for tenant={}: {}", event.getTenantIdValue(), ex.getMessage());
        }
        Optional<String> generated = generateGreeting(event.getTenantIdValue(), businessName);
        // Only drop the old audio once its replacement exists — a TTS outage must not leave the
        // cache pointing at deleted audio (code review).
        if (generated.isPresent() && previous != null) {
            String[] parts = previous.split("\\|", 2);
            if (parts.length == 2 && !parts[1].equals(generated.get())) {
                audioStore.delete(parts[1]);
            }
        }
    }

    private void regenerateInBackground(UUID tenantId, String businessName) {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(GREETING_LOCK_PREFIX + tenantId, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        executor.execute(() -> {
            try {
                generateGreeting(tenantId, businessName);
            } finally {
                try {
                    redisTemplate.delete(GREETING_LOCK_PREFIX + tenantId);
                } catch (Exception ignored) {
                    // lock expires on its own
                }
            }
        });
    }

    private Optional<String> generateGreeting(UUID tenantId, String businessName) {
        try {
            byte[] audio = ttsService.synthesize(greetingText(businessName), "en");
            String id = audioStore.store(audio, greetingTtl);
            redisTemplate.opsForValue().set(GREETING_KEY_PREFIX + tenantId, sha256(businessName) + "|" + id, greetingTtl);
            log.info("Generated voice greeting for tenant={}", tenantId);
            return Optional.of(id);
        } catch (Exception ex) {
            log.warn("Greeting TTS generation failed for tenant={}: {}", tenantId, ex.getMessage());
            return Optional.empty();
        }
    }

    private String businessName(UUID tenantId) {
        return tenantNamePort.getBusinessName(tenantId.toString())
                .filter(name -> !name.isBlank())
                .orElse(DEFAULT_BUSINESS_NAME);
    }

    static String greetingText(String businessName) {
        return String.format(GREETING_TEMPLATE, businessName);
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
