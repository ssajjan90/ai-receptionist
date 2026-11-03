package com.aireceptionist.voice;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.tenant.event.TenantOnboardedEvent;
import com.aireceptionist.voice.service.ExoMl;
import com.aireceptionist.voice.service.TtsService;
import com.aireceptionist.voice.service.VoiceAudioStore;
import com.aireceptionist.voice.service.VoiceCallbackUrls;
import com.aireceptionist.voice.service.VoiceResponseGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 6.2 (AC1, AC4) + code-review fixes: greeting caching never blocks the call path. */
class VoiceResponseGeneratorTest {

    private static final String BASE_URL = "https://voice.example.com";
    private static final String GREETING = "Hello! Welcome to Sharma Mobiles. How can I help you today?";

    private final TtsService ttsService = mock(TtsService.class);
    private final VoiceAudioStore audioStore = mock(VoiceAudioStore.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final TenantNamePort tenantNamePort = mock(TenantNamePort.class);

    private final UUID tenantId = UUID.randomUUID();
    private final String greetingKey = "voice:greeting:" + tenantId;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("Sharma Mobiles"));
        when(valueOps.setIfAbsent(eq("voice:greeting:lock:" + tenantId), anyString(), any(Duration.class))).thenReturn(true);
    }

    private VoiceResponseGenerator generator(String provider) {
        // Direct executor: background regeneration runs inline so the test can observe it.
        return new VoiceResponseGenerator(ttsService, audioStore, redisTemplate, tenantNamePort,
                new VoiceCallbackUrls(BASE_URL + "/", provider), Runnable::run, provider, 30, 10);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sayProviderUsesExotelBuiltInTtsWithoutCallingGoogle() {
        String verb = generator("say").getGreetingAudio(tenantId);

        assertThat(verb).startsWith("<Say").contains(GREETING);
        verifyNoInteractions(ttsService, audioStore);
    }

    @Test
    void cacheHitPlaysCachedAudioWithoutTts() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Sharma Mobiles") + "|audio-cached");
        when(audioStore.exists("audio-cached")).thenReturn(true);

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).isEqualTo("<Play>" + BASE_URL + "/webhooks/voice/audio/audio-cached</Play>");
        verify(ttsService, never()).synthesize(anyString(), anyString());
    }

    @Test
    void cacheMissAnswersWithSayImmediatelyAndRegeneratesInBackground() throws Exception {
        byte[] audio = {1, 2, 3};
        when(valueOps.get(greetingKey)).thenReturn(null);
        when(ttsService.synthesize(GREETING, "en")).thenReturn(audio);
        when(audioStore.store(eq(audio), any(Duration.class))).thenReturn("audio-1");

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).startsWith("<Say").contains(GREETING);
        verify(audioStore).store(audio, Duration.ofDays(30));
        verify(valueOps).set(greetingKey, sha256("Sharma Mobiles") + "|audio-1", Duration.ofDays(30));
        verify(redisTemplate).delete("voice:greeting:lock:" + tenantId);
    }

    @Test
    void concurrentCacheMissDoesNotSynthesizeTwice() {
        when(valueOps.get(greetingKey)).thenReturn(null);
        when(valueOps.setIfAbsent(eq("voice:greeting:lock:" + tenantId), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(generator("google").getGreetingAudio(tenantId)).startsWith("<Say");
        verify(ttsService, never()).synthesize(anyString(), anyString());
    }

    @Test
    void businessNameChangeMissesCacheAndRegenerates() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Old Name") + "|audio-old");
        when(audioStore.exists("audio-old")).thenReturn(true);
        when(ttsService.synthesize(anyString(), eq("en"))).thenReturn(new byte[]{9});
        when(audioStore.store(any(), any())).thenReturn("audio-new");

        assertThat(generator("google").getGreetingAudio(tenantId)).startsWith("<Say");
        verify(ttsService).synthesize(GREETING, "en");
    }

    @Test
    void redisFailureOnGreetingPathFallsBackToSayInsteadOfThrowing() {
        when(valueOps.get(greetingKey)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(generator("google").getGreetingAudio(tenantId)).startsWith("<Say").contains(GREETING);
    }

    @Test
    void ttsFailureDuringRegenerationStillAnswersWithSay() {
        when(valueOps.get(greetingKey)).thenReturn(null);
        when(ttsService.synthesize(anyString(), anyString())).thenThrow(new ExternalServiceException("down"));

        assertThat(generator("google").getGreetingAudio(tenantId)).startsWith("<Say").contains("Sharma Mobiles");
    }

    @Test
    void reOnboardingKeepsOldAudioWhenRegenerationFails() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Sharma Mobiles") + "|audio-old");
        when(ttsService.synthesize(anyString(), anyString())).thenThrow(new ExternalServiceException("down"));

        onboard(generator("google"), "Sharma Mobiles");

        verify(audioStore, never()).delete(anyString());
    }

    @Test
    void reOnboardingReplacesOldAudioWhenRegenerationSucceeds() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Sharma Mobiles") + "|audio-old");
        when(ttsService.synthesize(anyString(), anyString())).thenReturn(new byte[]{1});
        when(audioStore.store(any(), any())).thenReturn("audio-new");

        onboard(generator("google"), "Sharma Mobiles");

        verify(audioStore).delete("audio-old");
    }

    private void onboard(VoiceResponseGenerator generator, String name) throws Exception {
        Method listener = VoiceResponseGenerator.class.getDeclaredMethod("onTenantOnboarded", TenantOnboardedEvent.class);
        listener.setAccessible(true);
        listener.invoke(generator, new TenantOnboardedEvent(tenantId, name));
    }

    @Test
    void speakSynthesizesResponseWithShortTtlAndHindiVoice() {
        when(ttsService.synthesize("Namaste", "hi")).thenReturn(new byte[]{7});
        when(audioStore.store(any(), eq(Duration.ofMinutes(10)))).thenReturn("resp-1");

        assertThat(generator("google").speak("Namaste", "hi"))
                .isEqualTo("<Play>" + BASE_URL + "/webhooks/voice/audio/resp-1</Play>");
    }

    @Test
    void speakWithSayProviderEscapesXmlAndPicksLanguage() {
        String verb = generator("say").speak("Price < 500 & \"cheap\"", "hi");

        assertThat(verb).isEqualTo("<Say voice=\"woman\" language=\"hi-in\">Price &lt; 500 &amp; \"cheap\"</Say>");
        verifyNoInteractions(ttsService);
    }

    @Test
    void exoMlResponseWrapsVerbsAndStripsControlCharacters() {
        assertThat(ExoMl.response(ExoMl.say("a\u0001b", "en"), ExoMl.hangup()))
                .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>"
                        + "<Say voice=\"woman\" language=\"en-in\">ab</Say><Hangup/></Response>");
    }

    @Test
    void callbackUrlsAreAbsoluteWhenBaseUrlSetAndRelativeOtherwise() {
        assertThat(new VoiceCallbackUrls("https://voice.example.com/", "say").transcript())
                .isEqualTo("https://voice.example.com/webhooks/voice/transcript");
        assertThat(new VoiceCallbackUrls("", "say").transferTimeout()).isEqualTo("/webhooks/voice/transfer-timeout");
    }

    @Test
    void googleTtsWithoutPublicHttpsBaseUrlFailsFastAtStartup() {
        assertThatThrownBy(() -> new VoiceCallbackUrls("", "google")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VoiceCallbackUrls("http://localhost:8080", "google"))
                .isInstanceOf(IllegalStateException.class);
        new VoiceCallbackUrls("https://voice.example.com", "google");
    }
}
