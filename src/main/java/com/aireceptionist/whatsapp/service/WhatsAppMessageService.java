package com.aireceptionist.whatsapp.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.FrustrationDetectionService;
import com.aireceptionist.ai.service.IntentDetectionService;
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
import com.aireceptionist.whatsapp.event.LeadCaptureRequestedEvent;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhatsAppMessageService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageService.class);

    private static final String LEAD_PHASE_KEY_SUFFIX = ":lead_capture_phase";
    private static final String LEAD_INTENT_KEY_SUFFIX = ":lead_intent";
    private static final Duration LEAD_PHASE_TTL = Duration.ofMinutes(30);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?91[6-9]\\d{9}|[6-9]\\d{9})");

    private static final String CONSENT_MESSAGE_TEMPLATE =
            "To follow up with you, we'd need your name and contact number.\n\n" +
            "By sharing your details, you agree we may contact you about your enquiry regarding %s.\n\n" +
            "Please reply with your name and phone number, or type 'No' if you'd prefer not to share.";

    private final WhatsAppMessageRepository messageRepository;
    private final LanguageDetectionService languageDetectionService;
    private final LlmService llmService;
    private final ConversationContextService conversationContextService;
    private final WhatsAppNotificationService notificationService;
    private final TenantNamePort tenantNamePort;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;
    private final FrustrationDetectionService frustrationDetectionService;
    private final IntentDetectionService intentDetectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;

    public WhatsAppMessageService(WhatsAppMessageRepository messageRepository,
                                  LanguageDetectionService languageDetectionService,
                                  LlmService llmService,
                                  ConversationContextService conversationContextService,
                                  WhatsAppNotificationService notificationService,
                                  TenantNamePort tenantNamePort,
                                  TenantOwnerPhonePort tenantOwnerPhonePort,
                                  FrustrationDetectionService frustrationDetectionService,
                                  IntentDetectionService intentDetectionService,
                                  ApplicationEventPublisher eventPublisher,
                                  StringRedisTemplate redisTemplate) {
        this.messageRepository = messageRepository;
        this.languageDetectionService = languageDetectionService;
        this.llmService = llmService;
        this.conversationContextService = conversationContextService;
        this.notificationService = notificationService;
        this.tenantNamePort = tenantNamePort;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
        this.frustrationDetectionService = frustrationDetectionService;
        this.intentDetectionService = intentDetectionService;
        this.eventPublisher = eventPublisher;
        this.redisTemplate = redisTemplate;
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

            String finalResponse = applyLeadCaptureStateMachine(
                    tenantId, senderPhone, messageText, ownerPhone, result.response());

            WhatsAppMessage outbound = WhatsAppMessage.outboundAi(
                    event.getTenantIdValue(),
                    senderPhone,
                    finalResponse,
                    result.confidence(),
                    language.toLangCode()
            );
            messageRepository.save(outbound);

            try {
                notificationService.sendMessage(tenantId, senderPhone, finalResponse);
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

    private String applyLeadCaptureStateMachine(String tenantId, String senderPhone,
                                                  String customerMessage, String ownerPhone,
                                                  String aiResponse) {
        String phaseKey = leadPhaseKey(tenantId, senderPhone);
        String phase = redisTemplate.opsForValue().get(phaseKey);

        if ("CONSENT_SENT".equals(phase)) {
            if (intentDetectionService.isDecline(customerMessage)) {
                redisTemplate.opsForValue().set(phaseKey, "DECLINED", LEAD_PHASE_TTL);
                log.debug("Lead capture declined, tenant={}", tenantId);
                return aiResponse;
            }
            String[] parsed = parseNameAndPhone(customerMessage);
            if (parsed != null) {
                String customerName = parsed[0];
                String capturedPhone = parsed[1];
                String productIntent = redisTemplate.opsForValue().getAndDelete(
                        leadIntentKey(tenantId, senderPhone));
                if (productIntent == null) productIntent = "your enquiry";
                eventPublisher.publishEvent(new LeadCaptureRequestedEvent(
                        tenantId, senderPhone, customerName, capturedPhone, productIntent, ownerPhone,
                        Instant.now()));
                redisTemplate.opsForValue().set(phaseKey, "CAPTURED", LEAD_PHASE_TTL);
                log.info("Lead capture requested, tenant={}", tenantId);
            }
            return aiResponse;
        }

        if (phase == null && intentDetectionService.hasPurchaseIntent(customerMessage)) {
            String product = intentDetectionService.extractProductFromMessage(customerMessage, List.of())
                    .orElse("your enquiry");
            redisTemplate.opsForValue().set(leadIntentKey(tenantId, senderPhone), product, LEAD_PHASE_TTL);
            redisTemplate.opsForValue().set(phaseKey, "CONSENT_SENT", LEAD_PHASE_TTL);
            log.debug("Purchase intent detected, consent sent, tenant={}", tenantId);
            return aiResponse + "\n\n" + String.format(CONSENT_MESSAGE_TEMPLATE, product);
        }

        return aiResponse;
    }

    private String leadPhaseKey(String tenantId, String phone) {
        return "conv:" + tenantId + ":" + sha256(phone) + LEAD_PHASE_KEY_SUFFIX;
    }

    private String leadIntentKey(String tenantId, String phone) {
        return "conv:" + tenantId + ":" + sha256(phone) + LEAD_INTENT_KEY_SUFFIX;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String[] parseNameAndPhone(String message) {
        Matcher phoneMatcher = PHONE_PATTERN.matcher(message);
        if (!phoneMatcher.find()) return null;
        String phone = phoneMatcher.group(1);
        String remaining = message.replaceFirst(Pattern.quote(phoneMatcher.group(0)), "").trim();
        String name = remaining.isBlank() ? null : remaining.replaceAll("[,|/\\\\]", " ").trim();
        return new String[]{name, phone};
    }
}
