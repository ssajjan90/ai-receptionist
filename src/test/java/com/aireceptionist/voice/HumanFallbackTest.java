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
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.common.voice.VoiceTransferPort;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.domain.VoiceCallStatus;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import com.aireceptionist.voice.service.CallerPhone;
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
import org.springframework.data.redis.RedisConnectionFailureException;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 6.3 (AC1, AC2, AC5) + code-review fixes, all collaborators mocked. */
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
            mock(PlatformTransactionManager.class), exotelApiClient, Clock.systemUTC());

    private final UUID tenantId = UUID.randomUUID();
    private final String callSid = "CA-1";
    private final String caller = "+919800000001";
    private final String business = "+918000000000";
    private final String owner = "+919999999999";
    private VoiceCall call;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(languageDetection.detectLanguage(anyString())).thenReturn(Language.ENGLISH);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("Shop"));
        when(ownerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of(owner));
        call = VoiceCall.receive(tenantId, callSid, caller);
        when(repository.findByCallSid(callSid)).thenReturn(Optional.of(call));
    }

    private void llmReturns(String text, double confidence) {
        when(llmService.generateResponse(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new AiResponseResult(text, confidence));
    }

    private Object publishedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        return event.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can I speak to someone please",
            "CONNECT ME TO A PERSON",
            "I want a real person",
            "Get me the manager",
            "let me talk to a human"})
    void eachHumanSignalHoldsForTransferWithoutCallingLlm(String transcript) {
        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, transcript);

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.HOLD_FOR_TRANSFER);
        assertThat(result.responseText()).isEqualTo(ExotelCallService.HOLD_MESSAGE);
        verifyNoInteractions(llmService);
        verify(valueOps).set("voice:transfer:" + callSid, caller + "|" + business, Duration.ofSeconds(120));
        verify(valueOps).set("voice:transfer:waiting:" + tenantId, callSid, Duration.ofSeconds(120));
    }

    @Test
    void wordBoundaryAvoidsFalsePositives() {
        assertThat(ExotelCallService.isHumanRequest("Is this product humane and managerial-grade?")).isFalse();
        assertThat(ExotelCallService.isHumanRequest("What is the price?")).isFalse();
    }

    @Test
    void lowConfidenceHoldsAndAlertsOwnerWithReplyInstruction() {
        llmReturns("Let me get someone who knows.", 0.40);

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "Do you fix drones?");

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.HOLD_FOR_TRANSFER);
        VoiceHumanFallbackRequestedEvent alert = (VoiceHumanFallbackRequestedEvent) publishedEvent();
        assertThat(alert.getCallSid()).isEqualTo(callSid);
        assertThat(alert.getCallerPhone()).isEqualTo(caller);
        assertThat(alert.getOwnerPhone()).isEqualTo(owner);
        assertThat(alert.getReason()).contains("0.40");
    }

    @Test
    void confidentAnswerDoesNotTriggerFallback() {
        llmReturns("We open at 10.", 0.90);

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "When do you open?");

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.ANSWER);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void humanRequestWithNoOwnerPhoneSkipsHoldAndFollowsUp() {
        when(ownerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.empty());

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "real person please");

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.NO_AGENT);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.MISSED);
        VoiceCallbackRequestedEvent followUp = (VoiceCallbackRequestedEvent) publishedEvent();
        assertThat(followUp.getCallerPhone()).isEqualTo(caller);
    }

    @Test
    void lowConfidenceWithNoOwnerPhoneKeepsConversationGoing() {
        when(ownerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.empty());
        llmReturns("I'm not sure, sorry.", 0.30);

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "Do you fix drones?");

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.ANSWER);
        verifyNoInteractions(publisher);
    }

    @Test
    void redisFailureWhileParkingStillHoldsAndAlerts() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "real person please");

        assertThat(result.outcome()).isEqualTo(VoiceTurnResult.Outcome.HOLD_FOR_TRANSFER);
        assertThat(publishedEvent()).isInstanceOf(VoiceHumanFallbackRequestedEvent.class);
    }

    @Test
    void nonFiniteConfidenceAndBlankAnswerAreSanitised() {
        llmReturns("  ", Double.NaN);

        VoiceTurnResult result = service.handleTranscript(tenantId, callSid, caller, business, "hello?");

        assertThat(result.responseText()).isNotBlank();
        assertThat(result.confidence()).isZero();
        assertThat(call.getTranscript()).contains("AI (0.00): ").doesNotContain("null");
    }

    @Test
    void transcriptRecordsPerTurnConfidence() {
        llmReturns("We open at 10.", 0.876);

        service.handleTranscript(tenantId, callSid, caller, business, "When do you open?");

        assertThat(call.getTranscript()).isEqualTo("Caller: When do you open?\nAI (0.88): We open at 10.\n");
    }

    @Test
    void unusableCallerIdGetsNoConversationContext() {
        llmReturns("We open at 10.", 0.90);

        service.handleTranscript(tenantId, callSid, "anonymous", business, "When do you open?");

        verify(llmService).generateResponse(anyString(), anyString(), anyString(), isNull(), any());
        verifyNoInteractions(conversationContext);
    }

    @Test
    void timeoutWithMissingKeyStillFollowsUpUnlessTransferred() {
        when(valueOps.getAndDelete("voice:transfer:" + callSid)).thenReturn(null); // expired / lost

        assertThat(service.handleTransferTimeout(tenantId, callSid, caller)).isTrue();
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.MISSED);
        assertThat(publishedEvent()).isInstanceOf(VoiceCallbackRequestedEvent.class);
    }

    @Test
    void timeoutWithRedisDownStillFollowsUp() {
        when(valueOps.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.handleTransferTimeout(tenantId, callSid, caller)).isTrue();
        assertThat(publishedEvent()).isInstanceOf(VoiceCallbackRequestedEvent.class);
    }

    @Test
    void timeoutAfterTransferIsSilent() {
        call.markTransferred(java.time.Instant.now());

        assertThat(service.handleTransferTimeout(tenantId, callSid, caller)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void hangUpDuringHoldBecomesMissedWithFollowUp() {
        when(valueOps.getAndDelete("voice:transfer:" + callSid)).thenReturn(caller + "|" + business);

        service.handleCallEnded(tenantId, callSid, caller);

        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.MISSED);
        assertThat(call.getEndedAt()).isNotNull();
        assertThat(publishedEvent()).isInstanceOf(VoiceCallbackRequestedEvent.class);
    }

    @Test
    void normalHangUpFinalisesCallWithoutFollowUp() {
        service.handleCallEnded(tenantId, callSid, caller);

        assertThat(call.getEndedAt()).isNotNull();
        assertThat(call.getDurationSeconds()).isNotNull();
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.RECEIVED);
        verifyNoInteractions(publisher);
    }

    @Test
    void ownerTransferReplyBridgesWaitingCall() {
        when(valueOps.get("voice:transfer:waiting:" + tenantId)).thenReturn(callSid);
        when(valueOps.getAndDelete("voice:transfer:" + callSid)).thenReturn(caller + "|" + business);

        assertThat(service.transferWaitingCall(tenantId.toString(), owner)).isEqualTo(VoiceTransferPort.Outcome.TRANSFERRED);
        verify(exotelApiClient).connectCall(callSid, owner, business);
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.TRANSFERRED);
    }

    @Test
    void ownerTransferReplyWithNoWaitingCall() {
        assertThat(service.transferWaitingCall(tenantId.toString(), owner)).isEqualTo(VoiceTransferPort.Outcome.NO_CALL_WAITING);
        verifyNoInteractions(exotelApiClient);
    }

    @Test
    void ownerTransferReplyWhenExotelFails() {
        when(valueOps.get("voice:transfer:waiting:" + tenantId)).thenReturn(callSid);
        when(valueOps.getAndDelete("voice:transfer:" + callSid)).thenReturn(caller + "|" + business);
        doThrow(new ExternalServiceException("down")).when(exotelApiClient).connectCall(anyString(), anyString(), anyString());

        assertThat(service.transferWaitingCall(tenantId.toString(), owner)).isEqualTo(VoiceTransferPort.Outcome.FAILED);
        verify(valueOps).set("voice:transfer:" + callSid, caller + "|" + business, Duration.ofSeconds(60));
    }

    @Test
    void callerPhoneNormalisation() {
        assertThat(CallerPhone.normalize("09812345678")).isEqualTo("+919812345678");
        assertThat(CallerPhone.normalize("9812345678")).isEqualTo("+919812345678");
        assertThat(CallerPhone.normalize("919812345678")).isEqualTo("+919812345678");
        assertThat(CallerPhone.normalize("+91 98123-45678")).isEqualTo("+919812345678");
        assertThat(CallerPhone.isUsable(CallerPhone.normalize("anonymous"))).isFalse();
        assertThat(CallerPhone.isUsable(CallerPhone.normalize(""))).isFalse();
        assertThat(CallerPhone.isUsable("+919812345678")).isTrue();
    }

    @Test
    void ownerAlertTellsOwnerToReplyTransfer() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        VoiceCallAlertListener listener = new VoiceCallAlertListener(notifications, tenantNamePort);

        listener.onHumanFallbackRequested(new VoiceHumanFallbackRequestedEvent(tenantId.toString(), callSid, caller,
                owner, "Caller asked for a person"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendMessage(eq(tenantId.toString()), eq(owner), message.capture());
        assertThat(message.getValue()).contains(caller).contains("CA-1").contains("Reply TRANSFER");
    }

    @Test
    void callbackListenerFollowsUpWithCallerAndOwner() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        VoiceCallAlertListener listener = new VoiceCallAlertListener(notifications, tenantNamePort);

        listener.onCallbackRequested(new VoiceCallbackRequestedEvent(tenantId.toString(), callSid, caller, owner));

        verify(notifications).sendMessage(eq(tenantId.toString()), eq(caller), contains("Shop"));
        verify(notifications).sendMessage(eq(tenantId.toString()), eq(owner), contains(caller));
    }

    @Test
    void callbackWithHiddenCallerOnlyTellsOwner() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        VoiceCallAlertListener listener = new VoiceCallAlertListener(notifications, tenantNamePort);

        listener.onCallbackRequested(new VoiceCallbackRequestedEvent(tenantId.toString(), callSid, null, owner));

        verify(notifications).sendMessage(eq(tenantId.toString()), eq(owner), contains("hidden"));
        verify(notifications, never()).sendMessage(anyString(), isNull(), anyString());
    }

    @Test
    void ownerAlertSkippedWhenNoOwnerPhone() {
        WhatsAppNotificationService notifications = mock(WhatsAppNotificationService.class);
        new VoiceCallAlertListener(notifications, tenantNamePort).onHumanFallbackRequested(
                new VoiceHumanFallbackRequestedEvent(tenantId.toString(), callSid, caller, null, "x"));
        verifyNoInteractions(notifications);
    }
}
