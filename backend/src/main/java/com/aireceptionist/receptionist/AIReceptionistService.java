package com.aireceptionist.receptionist;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.chat.entity.ChatHistory;
import com.aireceptionist.chat.repository.ChatHistoryRepository;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.service.KnowledgeService;
import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.service.LeadService;
import com.aireceptionist.tenant.entity.Tenant;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AIReceptionistService {

    private static final Set<String> LEAD_INTENT_KEYWORDS = Set.of(
            "appointment",
            "book",
            "call me",
            "contact",
            "price",
            "interested"
    );

    private final TenantService tenantService;
    private final KnowledgeService knowledgeService;
    private final LeadService leadService;
    private final ChatHistoryRepository chatHistoryRepository;

    public OutboundMessageResponse processInboundMessage(InboundMessageRequest request) {
        validateInboundRequest(request);

        Tenant tenant = tenantService.getTenantOrThrow(request.getTenantId());
        List<KnowledgeBase> knowledgeEntries = knowledgeService.findActiveByTenant(request.getTenantId());

        String reply = buildKnowledgeBasedReply(request.getMessage(), knowledgeEntries, tenant);
        boolean leadCreated = isLeadIntent(request.getMessage());

        if (leadCreated) {
            leadService.createInternal(
                    request.getTenantId(),
                    request.getCustomerPhone(),
                    request.getMessage(),
                    mapChannelToLeadSource(request.getChannel())
            );
        }

        // Existing conversation persistence uses ChatHistory.
        // TODO: switch to a dedicated conversation entity if/when introduced.
        ChatHistory history = ChatHistory.builder()
                .tenantId(request.getTenantId())
                .customerPhone(request.getCustomerPhone())
                .channel(mapToChatChannel(request.getChannel()))
                .userMessage(request.getMessage())
                .aiResponse(reply)
                .intent(leadCreated ? "LEAD_INTENT" : "GENERAL_QUERY")
                .build();

        ChatHistory savedHistory = chatHistoryRepository.save(history);

        return OutboundMessageResponse.builder()
                .tenantId(request.getTenantId())
                .channel(request.getChannel())
                .customerPhone(request.getCustomerPhone())
                .responseMessage(reply)
                .leadCreated(leadCreated)
                .conversationId(savedHistory.getId().toString())
                .build();
    }

    private void validateInboundRequest(InboundMessageRequest request) {
        if (request == null) {
            throw new BadRequestException("Inbound message request is required.");
        }
        if (request.getTenantId() == null) {
            throw new BadRequestException("Tenant ID is required.");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BadRequestException("Message is required.");
        }
        if (request.getChannel() == null) {
            throw new BadRequestException("Channel is required.");
        }
    }

    private String buildKnowledgeBasedReply(String message, List<KnowledgeBase> entries, Tenant tenant) {
        String normalizedMessage = normalize(message);

        for (KnowledgeBase entry : entries) {
            String question = normalize(entry.getQuestion());
            if (!question.isBlank() && (normalizedMessage.contains(question) || question.contains(normalizedMessage))) {
                return entry.getAnswer();
            }
        }

        for (KnowledgeBase entry : entries) {
            if (hasKeywordOverlap(normalizedMessage, normalize(entry.getQuestion()))) {
                return entry.getAnswer();
            }
        }

        return "Thanks for contacting " + defaultName(tenant.getName()) + ". " +
                "I have noted your message and our team will get back to you shortly.";
    }

    private boolean hasKeywordOverlap(String input, String question) {
        if (input.isBlank() || question.isBlank()) {
            return false;
        }
        String[] inputParts = input.split("\\s+");
        for (String part : inputParts) {
            if (part.length() >= 4 && question.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLeadIntent(String message) {
        String normalized = normalize(message);
        return LEAD_INTENT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private Lead.LeadSource mapChannelToLeadSource(CommunicationChannel channel) {
        return switch (channel) {
            case WHATSAPP -> Lead.LeadSource.WHATSAPP;
            case VOICE_CALL -> Lead.LeadSource.VOICE;
            default -> Lead.LeadSource.CHAT;
        };
    }

    private ChatHistory.ChatChannel mapToChatChannel(CommunicationChannel channel) {
        return switch (channel) {
            case WHATSAPP -> ChatHistory.ChatChannel.WHATSAPP;
            case VOICE_CALL -> ChatHistory.ChatChannel.VOICE;
            default -> ChatHistory.ChatChannel.CHAT;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String defaultName(String tenantName) {
        return (tenantName == null || tenantName.isBlank()) ? "our business" : tenantName;
    }
}
