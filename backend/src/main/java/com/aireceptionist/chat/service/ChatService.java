package com.aireceptionist.chat.service;

import com.aireceptionist.chat.dto.ChatRequest;
import com.aireceptionist.chat.dto.ChatResponse;
import com.aireceptionist.chat.entity.ChatHistory;
import com.aireceptionist.chat.repository.ChatHistoryRepository;
import com.aireceptionist.integration.openai.OpenAIService;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.service.KnowledgeService;
import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.service.LeadService;
import com.aireceptionist.tenant.entity.Tenant;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String PROMPT_TEMPLATE = """
            You are an AI receptionist for {businessName}.
            Industry: {industry}
            Working hours: {workingHours}
            Default language: {defaultLanguage}
            Supported languages: {supportedLanguages}

            Business knowledge base:
            {knowledgeBase}

            Rules:
            - Detect customer language and reply in the same language.
            - If user mixes English and local language, reply naturally in same style.
            - Answer only using provided business knowledge.
            - Do not invent prices, timings, or availability.
            - Always collect name and phone number for leads.
            - For appointment booking, ask date, time, service, name, and phone.
            - If unsure, offer human handoff.
            - Be polite, short, and professional.
            """;

    private final TenantService tenantService;
    private final KnowledgeService knowledgeService;
    private final LeadService leadService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final OpenAIService openAIService;

    @Transactional
    public ChatResponse processChat(ChatRequest request) {
        Tenant tenant = tenantService.getTenantOrThrow(request.getTenantId());
        List<KnowledgeBase> knowledgeList = knowledgeService.findActiveByTenant(request.getTenantId());

        String systemPrompt = buildPrompt(tenant, knowledgeList);
        String aiReply = openAIService.chat(systemPrompt, request.getMessage());

        String intent = detectIntent(request.getMessage());

        boolean leadCreated = false;
        if (isAppointmentOrLeadIntent(intent)) {
            leadService.createInternal(
                    request.getTenantId(),
                    request.getCustomerPhone(),
                    request.getMessage(),
                    mapChannelToLeadSource(request.getChannel())
            );
            leadCreated = true;
            log.info("Auto-created lead for tenant {} from channel {}", request.getTenantId(), request.getChannel());
        }

        ChatHistory history = ChatHistory.builder()
                .tenantId(request.getTenantId())
                .customerPhone(request.getCustomerPhone())
                .channel(request.getChannel())
                .userMessage(request.getMessage())
                .aiResponse(aiReply)
                .intent(intent)
                .build();
        chatHistoryRepository.save(history);

        return ChatResponse.builder()
                .reply(aiReply)
                .intent(intent)
                .leadCreated(leadCreated)
                .appointmentCreated(false)
                .build();
    }

    private String buildPrompt(Tenant tenant, List<KnowledgeBase> knowledgeList) {
        StringBuilder kb = new StringBuilder();
        for (KnowledgeBase entry : knowledgeList) {
            kb.append("Q: ").append(entry.getQuestion()).append("\n");
            kb.append("A: ").append(entry.getAnswer()).append("\n\n");
        }
        if (kb.isEmpty()) {
            kb.append("No specific knowledge base configured yet.");
        }

        return PROMPT_TEMPLATE
                .replace("{businessName}", orDefault(tenant.getName(), "this business"))
                .replace("{industry}", orDefault(tenant.getIndustry(), "General"))
                .replace("{workingHours}", orDefault(tenant.getWorkingHours(), "Please contact us for hours"))
                .replace("{defaultLanguage}", orDefault(tenant.getDefaultLanguage(), "English"))
                .replace("{supportedLanguages}", orDefault(tenant.getSupportedLanguages(), "English"))
                .replace("{knowledgeBase}", kb.toString().trim());
    }

    private String detectIntent(String message) {
        String lower = message.toLowerCase();

        if (containsAny(lower, "appointment", "book", "schedule", "reserve", "slot", "meeting", "visit")) {
            return "APPOINTMENT_BOOKING";
        }
        if (containsAny(lower, "price", "cost", "fee", "charge", "rate", "how much", "quote")) {
            return "PRICE_QUERY";
        }
        if (containsAny(lower, "human", "agent", "person", "staff", "manager", "speak to", "talk to")) {
            return "HUMAN_HANDOFF";
        }
        if (containsAny(lower, "what", "how", "when", "where", "who", "which", "do you", "tell me", "info")) {
            return "GENERAL_QUERY";
        }
        return "UNKNOWN";
    }

    private boolean isAppointmentOrLeadIntent(String intent) {
        return "APPOINTMENT_BOOKING".equals(intent) || "HUMAN_HANDOFF".equals(intent);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private Lead.LeadSource mapChannelToLeadSource(ChatHistory.ChatChannel channel) {
        return switch (channel) {
            case WHATSAPP -> Lead.LeadSource.WHATSAPP;
            case VOICE -> Lead.LeadSource.VOICE;
            default -> Lead.LeadSource.CHAT;
        };
    }

    private String orDefault(String value, String defaultValue) {
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
