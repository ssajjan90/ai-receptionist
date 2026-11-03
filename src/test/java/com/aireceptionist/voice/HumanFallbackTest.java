package com.aireceptionist.voice;

import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.LanguageDetectionService;
import com.aireceptionist.ai.service.LlmService;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.event.VoiceCallbackRequestedEvent;
import com.aireceptionist.common.event.VoiceHumanFallbackRequestedEvent;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import com.aireceptionist.voice.service.ExotelApiClient;
import com.aireceptionist.voice.service.ExotelCallService;
import com.aireceptionist.voice.service.VoiceTurnResult;
import com.aireceptionist.whatsapp.service.VoiceCallAlertListener;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 6.3 (AC1, AC2, AC5): fallback detection and owner/caller alerts, all collaborators mocked. */
class HumanFallbackTest {

    private final VoiceCallRepository repository = mock(VoiceCallRepository.class);
    private final LlmService llmService = mock(LlmService.class);
    private final LanguageDetectionService languageDetection = mock(LanguageDetectionService.class);
    private final ConversationContextService conversationContext = mock(ConversationContextService.class);
    private final TenantNamePort tenantNamePort = mock(TenantNamePort.class);
    private final TenantOwnerPhonePort ownerPhonePort = mock(TenantOwnerPhonePort.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ExotelApiClient exotelApiClient = mock(ExotelApiClient.class);

    private final ExotelCallService service = new ExotelCallService(repository, llmService, languageDetection,
            conversationContext, tenantNamePort, ownerPhonePort, redisTemplate, publisher,
            mock(PlatformTransactionManager.class), exotelApiClient, "https://voice.example.com", Clock.systemUTC());

    private final UUID tenantId = UUID.randomUUID();
    private final String callSid = "CA-1";
    private final String caller = "+919800000001";
    private final String business = "+918000000000";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(languageDetection.detectLanguage(anyString())).thenReturn(Language.ENGLISH);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("Shop"));
        when(ownerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of("+919999999999"));
        when(repository.findByCallSid(callSid)).thenReturn(Optional.of(VoiceCall.receive(tenantId, callSid, caller)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can I speak to someone please",
            "CONNECT ME TO A PERSON",
            "I want a real person",
            "Get me the manager",
            "let me talk to a human"})
    void eachHumanSignalTriggersFallbackWithoutCallingLlm(String transcript) {
        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, transcript);

        assertThat(result.humanFallbackRequired()).isTrue();
        assertThat(result.responseText()).isEqualTo(ExotelCallService.HOLD_MESSAGE);
        verifyNoInteractions(llmService);
        verify(valueOps).set("voice:transfer:" + callSid, caller + "|" + business, Duration.ofSeconds(120));
    }

    @Test
    void wordBoundaryAvoidsFalsePositives() {
        assertThat(ExotelCallService.isHumanRequest("Is this product humane and managerial-grade?")).isFalse();
        assertThat(ExotelCallService.isHumanRequest("What is the price?")).isFalse();
    }

    @Test
    void lowConfidenceTriggersFallbackAndOwnerAlertEvent() {
        when(llmService.generateResponse(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiResponseResult("Let me get someone who knows.", 0.40, true));

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "Do you fix drones?");

        assertThat(result.humanFallbackRequired()).isTrue();
        verify(valueOps).set(eq("voice:transfer:" + callSid), anyString(), eq(Duration.ofSeconds(120)));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        VoiceHumanFallbackRequestedEvent alert = (VoiceHumanFallbackRequestedEvent) event.getValue();
        assertThat(alert.getCallSid()).isEqualTo(callSid);
        assertThat(alert.getCallerPhone()).isEqualTo(caller);
        assertThat(alert.getOwnerPhone()).isEqualTo("+919999999999");
        assertThat(alert.getTransferUrl()).isEqualTo("https://voice.example.com/v1/voice/calls/CA-1/transfer");
        assertThat(alert.getReason()).contains("0.40");
    }

    @Test
    void confidentAnswerDoesNotTriggerFallback() {
        when(llmService.generateResponse(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiResponseResult("We open at 10.", 0.90));

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "When do you open?");

        assertThat(result.humanFallbackRequired()).isFalse();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void ownerAlertListenerSendsCallerCallSidAndTransferLink() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        VoiceCallAlertListener listener = new VoiceCallAlertListener(notifications, tenantNamePort);

        listener.onHumanFallbackRequested(new VoiceHumanFallbackRequestedEvent(tenantId.toString(), callSid, caller,
                "+919999999999", "Caller asked for a person", "https://voice.example.com/v1/voice/calls/CA-1/transfer"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendMessage(eq(tenantId.toString()), eq("+919999999999"), message.capture());
        assertThat(message.getValue())
                .contains(caller).contains("CA-1")
                .contains("Transfer Call: https://voice.example.com/v1/voice/calls/CA-1/transfer");
    }

    @Test
    void callbackListenerFollowsUpWithCallerAndOwner() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        VoiceCallAlertListener listener = new VoiceCallAlertListener(notifications, tenantNamePort);

        listener.onCallbackRequested(new VoiceCallbackRequestedEvent(tenantId.toString(), callSid, caller, "+919999999999"));

        verify(notifications).sendMessage(eq(tenantId.toString()), eq(caller), contains("Shop"));
        verify(notifications).sendMessage(eq(tenantId.toString()), eq("+919999999999"), contains(caller));
    }

    @Test
    void ownerAlertSkippedWhenNoOwnerPhone() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        new VoiceCallAlertListener(notifications, tenantNamePort).onHumanFallbackRequested(
                new VoiceHumanFallbackRequestedEvent(tenantId.toString(), callSid, caller, null, "x", "u"));
        verifyNoInteractions(notifications);
    }
}
