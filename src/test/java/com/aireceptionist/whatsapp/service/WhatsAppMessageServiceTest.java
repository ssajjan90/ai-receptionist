package com.aireceptionist.whatsapp.service;

import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.FrustrationDetectionService;
import com.aireceptionist.ai.service.IntentDetectionService;
import com.aireceptionist.ai.service.LanguageDetectionService;
import com.aireceptionist.ai.service.LlmService;
import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.2 (AC1, AC6): fast, mock-based coverage of the suspended-tenant guard in
 * {@code WhatsAppMessageService.onInboundMessage} — deliberately independent of the
 * Testcontainers/Redis-backed integration path (see AdminTenantActionsTest, and deferred W84 for
 * why that path is currently unreliable in this environment).
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppMessageServiceTest {

    private static final String UNAVAILABLE_MESSAGE = "This service is temporarily unavailable. Please try again later.";

    @Mock WhatsAppMessageRepository messageRepository;
    @Mock LanguageDetectionService languageDetectionService;
    @Mock LlmService llmService;
    @Mock ConversationContextService conversationContextService;
    @Mock WhatsAppNotificationService notificationService;
    @Mock TenantNamePort tenantNamePort;
    @Mock TenantOwnerPhonePort tenantOwnerPhonePort;
    @Mock FrustrationDetectionService frustrationDetectionService;
    @Mock IntentDetectionService intentDetectionService;
    @Mock GetTenantStatusUseCase getTenantStatusUseCase;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StringRedisTemplate redisTemplate;

    private WhatsAppMessageService newService() {
        return new WhatsAppMessageService(messageRepository, languageDetectionService, llmService,
                conversationContextService, notificationService, tenantNamePort, tenantOwnerPhonePort,
                frustrationDetectionService, intentDetectionService, getTenantStatusUseCase,
                eventPublisher, redisTemplate);
    }

    @Test
    void suspendedTenantGetsFixedReplyWithoutAiProcessing() {
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.of("SUSPENDED"));

        newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), "+919876500000", "What is the price?", "PHONE_1", Instant.now()));

        verify(notificationService).sendMessage(tenantId.toString(), "+919876500000", UNAVAILABLE_MESSAGE);
        verify(llmService, never()).generateResponse(any(), any(), any(), any(), any());
        verify(llmService, never()).generateEmpathyResponse(any(), any(), any(), any(), any());

        // Saved twice, matching this class's existing pattern elsewhere: once before sending,
        // once after marking sent/failed — both capture the same content.
        ArgumentCaptor<WhatsAppMessage> saved = ArgumentCaptor.forClass(WhatsAppMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo(UNAVAILABLE_MESSAGE);
    }

    @Test
    void paymentSuspendedTenantAlsoGetsFixedReplyWithoutAiProcessing() {
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.of("PAYMENT_SUSPENDED"));

        newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), "+919876500001", "Hi", "PHONE_1", Instant.now()));

        verify(notificationService).sendMessage(tenantId.toString(), "+919876500001", UNAVAILABLE_MESSAGE);
        verify(llmService, never()).generateResponse(any(), any(), any(), any(), any());
    }

    @Test
    void unavailableReplyIsPersistedEvenWhenDeliveryFails() {
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.of("SUSPENDED"));
        org.mockito.Mockito.doThrow(new RuntimeException("WhatsApp API down"))
                .when(notificationService).sendMessage(anyString(), anyString(), anyString());

        newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), "+919876500002", "Hi", "PHONE_1", Instant.now()));

        verify(messageRepository, org.mockito.Mockito.times(2)).save(any(WhatsAppMessage.class));
    }

    @Test
    void terminatedTenantStillGetsFixedReplyWithoutAiProcessing() {
        // TERMINATED is recoverable-adjacent (30-day grace period before erasure) — unlike ERASED,
        // it keeps the existing notify-and-persist behavior.
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.of("TERMINATED"));

        newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), "+919876500004", "Hi", "PHONE_1", Instant.now()));

        verify(notificationService).sendMessage(tenantId.toString(), "+919876500004", UNAVAILABLE_MESSAGE);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(any(WhatsAppMessage.class));
    }

    /**
     * Story 5.5 code review follow-up (2026-09-01): unlike SUSPENDED/PAYMENT_SUSPENDED/TERMINATED,
     * ERASED must not send or persist anything — doing so would write a brand-new whatsapp_messages
     * row (tenant_id + the real sender's phone number) for a tenant whose data was just hard-deleted
     * by DPDP erasure, silently re-accumulating PII every time someone messages the old number.
     */
    @Test
    void erasedTenantGetsNoReplyAndNothingIsPersisted() {
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.of("ERASED"));

        newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                tenantId, UUID.randomUUID().toString(), "+919876500005", "Hi", "PHONE_1", Instant.now()));

        verify(notificationService, never()).sendMessage(anyString(), anyString(), anyString());
        verify(messageRepository, never()).save(any(WhatsAppMessage.class));
        verify(llmService, never()).generateResponse(any(), any(), any(), any(), any());
    }

    /**
     * Regression (code review, 2026-09-01): {@code getStatus} returning empty (no matching tenant
     * row — a real, reachable case, not hypothetical: many pre-existing tests, e.g.
     * {@code LeadCaptureFlowTest}, publish this event without ever seeding a tenant) used to throw
     * a {@code NullPointerException} from {@code Set.of(...).contains(null)}, silently swallowed by
     * the async listener — the message was just dropped with no visible error. Blank message text
     * is used here deliberately to reach the early-return path with minimal mocking; the guard
     * itself (not the rest of the pipeline) is what this test proves doesn't throw.
     */
    @Test
    void unknownTenantStatusDoesNotThrowAndDoesNotTakeTheUnavailablePath() {
        UUID tenantId = UUID.randomUUID();
        when(getTenantStatusUseCase.getStatus(tenantId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() ->
                newService().onInboundMessage(new InboundWhatsAppMessageEvent(
                        tenantId, UUID.randomUUID().toString(), "+919876500003", "  ", "PHONE_1", Instant.now())))
                .doesNotThrowAnyException();

        verify(notificationService, never()).sendMessage(anyString(), anyString(), eq(UNAVAILABLE_MESSAGE));
    }
}
