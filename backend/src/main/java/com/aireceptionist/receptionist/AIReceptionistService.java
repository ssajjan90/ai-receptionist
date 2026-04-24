package com.aireceptionist.receptionist;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.conversation.entity.Conversation;
import com.aireceptionist.conversation.service.ConversationService;
import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.service.KnowledgeService;
import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.service.LeadService;
import com.aireceptionist.tenant.entity.Tenant;
import com.aireceptionist.tenant.repository.TenantRepository;
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

    private static final String NO_MATCH_FALLBACK = "Thank you for contacting us. Please share your name and phone number. Our team will get back to you shortly.";

    private static final Set<String> LEAD_INTENT_KEYWORDS = Set.of(
            "appointment",
            "book",
            "call me",
            "contact",
            "price",
            "interested"
    );

    private final TenantRepository tenantRepository;
    private final KnowledgeService knowledgeService;
    private final LeadService leadService;
    private final ConversationService conversationService;

    public OutboundMessageResponse processInboundMessage(InboundMessageRequest request) {
        validateInboundRequest(request);

        Tenant tenant = tenantRepository.findById(request.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", request.getTenantId()));

        Conversation conversation = conversationService.findOrCreateConversation(
                tenant.getId(),
                request.getChannel(),
                request.getCustomerPhone()
        );
        conversationService.saveInbound(conversation, request.getMessage());

        List<KnowledgeBase> matches = knowledgeService.findBestMatches(
                tenant.getId(),
                resolveIndustry(tenant.getIndustry()),
                request.getMessage(),
                tenant.getDefaultLanguage()
        );

        String responseMessage = matches.isEmpty() ? NO_MATCH_FALLBACK : matches.get(0).getAnswer();

        boolean leadCreated = isLeadIntent(request.getMessage());
        if (leadCreated) {
            leadService.createInternal(
                    tenant.getId(),
                    request.getCustomerPhone(),
                    request.getMessage(),
                    mapChannelToLeadSource(request.getChannel())
            );
        }

        conversationService.saveOutbound(conversation, responseMessage);

        return OutboundMessageResponse.builder()
                .tenantId(tenant.getId())
                .channel(request.getChannel())
                .customerPhone(request.getCustomerPhone())
                .responseMessage(responseMessage)
                .leadCreated(leadCreated)
                .conversationId(String.valueOf(conversation.getId()))
                .build();
    }

    private IndustryType resolveIndustry(String industry) {
        if (industry == null || industry.isBlank()) {
            return IndustryType.CLINIC;
        }

        String normalized = industry.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return IndustryType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return IndustryType.CLINIC;
        }
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
    }

    private String normalizeMessage(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isLeadIntent(String message) {
        String normalizedMessage = normalizeMessage(message);
        return LEAD_INTENT_KEYWORDS.stream().anyMatch(normalizedMessage::contains);
    }

    private Lead.LeadSource mapChannelToLeadSource(CommunicationChannel channel) {
        if (channel == null) {
            return Lead.LeadSource.CHAT;
        }

        return switch (channel) {
            case WHATSAPP -> Lead.LeadSource.WHATSAPP;
            case VOICE_CALL -> Lead.LeadSource.VOICE;
            default -> Lead.LeadSource.CHAT;
        };
    }
}
