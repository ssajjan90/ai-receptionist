package com.aireceptionist.voice;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.voice.service.ExoMl;
import com.aireceptionist.voice.service.TtsService;
import com.aireceptionist.voice.service.VoiceAudioStore;
import com.aireceptionist.voice.service.VoiceResponseGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 6.2 (AC1, AC4): greeting generation/caching and response speech, with TTS mocked. */
class VoiceResponseGeneratorTest {

    private static final String BASE_URL = "https://voice.example.com";

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
    }

    private VoiceResponseGenerator generator(String provider) {
        return new VoiceResponseGenerator(ttsService, audioStore, redisTemplate, tenantNamePort,
                provider, BASE_URL + "/", 30, 10);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sayProviderUsesExotelBuiltInTtsWithoutCallingGoogle() {
        String verb = generator("say").getGreetingAudio(tenantId);

        assertThat(verb).startsWith("<Say").contains("Hello! Welcome to Sharma Mobiles. How can I help you today?");
        verifyNoInteractions(ttsService, audioStore);
    }

    @Test
    void cacheMissGeneratesGreetingViaTtsAndCachesIt() throws Exception {
        byte[] audio = {1, 2, 3};
        when(valueOps.get(greetingKey)).thenReturn(null);
        when(ttsService.synthesize(anyString(), eq("en"))).thenReturn(audio);
        when(audioStore.store(eq(audio), any(Duration.class))).thenReturn("audio-1");

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).isEqualTo("<Play>" + BASE_URL + "/webhooks/voice/audio/audio-1</Play>");
        verify(ttsService).synthesize("Hello! Welcome to Sharma Mobiles. How can I help you today?", "en");
        verify(audioStore).store(audio, Duration.ofDays(30));
        verify(valueOps).set(greetingKey, sha256("Sharma Mobiles") + "|audio-1", Duration.ofDays(30));
    }

    @Test
    void cacheHitSkipsTtsCall() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Sharma Mobiles") + "|audio-cached");
        when(audioStore.exists("audio-cached")).thenReturn(true);

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).isEqualTo("<Play>" + BASE_URL + "/webhooks/voice/audio/audio-cached</Play>");
        verify(ttsService, never()).synthesize(anyString(), anyString());
    }

    @Test
    void businessNameChangeRegeneratesGreeting() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Old Name") + "|audio-old");
        when(audioStore.exists("audio-old")).thenReturn(true);
        when(ttsService.synthesize(anyString(), eq("en"))).thenReturn(new byte[]{9});
        when(audioStore.store(any(), any())).thenReturn("audio-new");

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).contains("audio-new");
        verify(ttsService).synthesize("Hello! Welcome to Sharma Mobiles. How can I help you today?", "en");
    }

    @Test
    void expiredCachedAudioRegeneratesGreeting() throws Exception {
        when(valueOps.get(greetingKey)).thenReturn(sha256("Sharma Mobiles") + "|audio-gone");
        when(audioStore.exists("audio-gone")).thenReturn(false);
        when(ttsService.synthesize(anyString(), eq("en"))).thenReturn(new byte[]{9});
        when(audioStore.store(any(), any())).thenReturn("audio-new");

        assertThat(generator("google").getGreetingAudio(tenantId)).contains("audio-new");
    }

    @Test
    void ttsFailureFallsBackToSaySoTheCallStillConnects() {
        when(valueOps.get(greetingKey)).thenReturn(null);
        when(ttsService.synthesize(anyString(), anyString())).thenThrow(new ExternalServiceException("down"));

        String verb = generator("google").getGreetingAudio(tenantId);

        assertThat(verb).startsWith("<Say").contains("Sharma Mobiles");
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
}
