package com.aireceptionist.whatsapp.service;

import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.LanguageDetectionService;
import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.LlmService;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.whatsapp.domain.MessageDirection;
import com.aireceptionist.whatsapp.domain.SenderType;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppMessageService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageService.class);

    private final WhatsAppMessageRepository messageRepository;
    private final LanguageDetectionService languageDetectionService;
    private final LlmService llmService;
    private final ConversationContextService conversationContextService;
    private final WhatsAppNotificationService notificationService;

    public WhatsAppMessageService(WhatsAppMessageRepository messageRepository,
                                  LanguageDetectionService languageDetectionService,
                                  LlmService llmService,
                                  ConversationContextService conversationContextService,
                                  WhatsAppNotificationService notificationService) {
        this.messageRepository = messageRepository;
        this.languageDetectionService = languageDetectionService;
        this.llmService = llmService;
        this.conversationContextService = conversationContextService;
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    void onInboundMessage(InboundWhatsAppMessageEvent event) {
        String tenantId = event.getTenantId();
        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);
        try {
            String messageText = event.getMessageText();
            String senderPhone = event.getSenderPhone();

            WhatsAppMessage inbound = WhatsAppMessage.inboundCustomer(
                    event.getTenantIdValue(),
                    event.getMessageId(),
                    senderPhone,
                    messageText
            );
            messageRepository.save(inbound);

            if (messageText == null || messageText.isBlank()) {
                log.debug("Skipping AI pipeline for blank/non-text message, tenant={}", tenantId);
                return;
            }

            Language language = languageDetectionService.detectLanguage(messageText);
            log.debug("Detected language={} for tenant={}", language, tenantId);

            conversationContextService.addTurn(tenantId, senderPhone, "customer", messageText);

            AiResponseResult result = llmService.generateResponse(tenantId, messageText, senderPhone, language);
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

            notificationService.sendMessage(tenantId, senderPhone, result.response());
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
        }
    }
}
