package com.aireceptionist.whatsapp.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.FrustrationDetectionService;
import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.LanguageDetectionService;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.LlmService;
import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.event.FrustrationDetectedEvent;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WhatsAppMessageService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageService.class);

    private final WhatsAppMessageRepository messageRepository;
    private final LanguageDetectionService languageDetectionService;
    private final LlmService llmService;
    private final ConversationContextService conversationContextService;
    private final WhatsAppNotificationService notificationService;
    private final TenantNamePort tenantNamePort;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;
    private final FrustrationDetectionService frustrationDetectionService;
    private final ApplicationEventPublisher eventPublisher;

    public WhatsAppMessageService(WhatsAppMessageRepository messageRepository,
                                  LanguageDetectionService languageDetectionService,
                                  LlmService llmService,
                                  ConversationContextService conversationContextService,
                                  WhatsAppNotificationService notificationService,
                                  TenantNamePort tenantNamePort,
                                  TenantOwnerPhonePort tenantOwnerPhonePort,
                                  FrustrationDetectionService frustrationDetectionService,
                                  ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.languageDetectionService = languageDetectionService;
        this.llmService = llmService;
        this.conversationContextService = conversationContextService;
        this.notificationService = notificationService;
        this.tenantNamePort = tenantNamePort;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
        this.frustrationDetectionService = frustrationDetectionService;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onInboundMessage(InboundWhatsAppMessageEvent event) {
        String tenantId = event.getTenantId();
        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);
        try {
            String messageText = event.getMessageText();
            String senderPhone = event.getSenderPhone();

            if (messageText == null || messageText.isBlank()) {
                log.debug("Skipping AI pipeline for blank/non-text message, tenant={}", tenantId);
                WhatsAppMessage inbound = WhatsAppMessage.inboundCustomer(
                        event.getTenantIdValue(),
                        event.getMessageId(),
                        senderPhone,
                        messageText
                );
                messageRepository.save(inbound);
                return;
            }

            WhatsAppMessage inbound = WhatsAppMessage.inboundCustomer(
                    event.getTenantIdValue(),
                    event.getMessageId(),
                    senderPhone,
                    messageText
            );
            messageRepository.save(inbound);

            Language language = languageDetectionService.detectLanguage(messageText);
            log.debug("Detected language={} for tenant={}", language, tenantId);

            conversationContextService.addTurn(tenantId, senderPhone, "customer", messageText);
            conversationContextService.setSessionLanguage(tenantId, senderPhone, language.toLangCode());

            String businessName = tenantNamePort.getBusinessName(tenantId).orElse("Business");
            String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenantId).orElse(null);

            boolean empathyMode = conversationContextService.isEmpathyMode(tenantId, senderPhone);
            if (!empathyMode && frustrationDetectionService.isFrustrated(messageText)) {
                List<String> signals = frustrationDetectionService.getFrustrationSignals(messageText);
                log.info("Frustration detected for tenant={}, signals={}", tenantId, signals);
                conversationContextService.setEmpathyMode(tenantId, senderPhone);
                empathyMode = true;

                List<ConversationTurn> history =
                        conversationContextService.getHistory(tenantId, senderPhone, 3);
                List<String> historyStrings = history.stream()
                        .map(t -> t.role() + ": " + t.content())
                        .toList();
                eventPublisher.publishEvent(new FrustrationDetectedEvent(
                        tenantId, senderPhone, messageText, ownerPhone, historyStrings));
            }

            AiResponseResult result = empathyMode
                    ? llmService.generateEmpathyResponse(tenantId, businessName, messageText, senderPhone, language)
                    : llmService.generateResponse(tenantId, businessName, messageText, senderPhone, language);
            log.debug("AI response confidence={} for tenant={}", result.confidence(), tenantId);

            conversationContextService.addTurn(tenantId, senderPhone, "ai", result.response());

            WhatsAppMessage outbound = WhatsAppMessage.outboundAi(
                    event.getTenantIdValue(),
                    senderPhone,
                    result.response(),
                    result.confidence(),
                    language.toLangCode()
            );
            messageRepository.save(outbound);

            try {
                notificationService.sendMessage(tenantId, senderPhone, result.response());
                outbound.markSent();
            } catch (Exception ex) {
                log.warn("Failed to deliver WhatsApp message for tenant={}: {}", tenantId, ex.getMessage());
                outbound.markFailed();
            }
            messageRepository.save(outbound);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
        }
    }
}
