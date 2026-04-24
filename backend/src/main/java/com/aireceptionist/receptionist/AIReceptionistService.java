package com.aireceptionist.receptionist;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.repository.KnowledgeBaseRepository;
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

    private static final String DEFAULT_REPLY = "Thank you for contacting us. We will get back to you shortly.";

    private static final Set<String> LEAD_INTENT_KEYWORDS = Set.of(
            "appointment",
            "book",
            "call me",
            "contact",
            "price",
            "interested"
    );

    private final TenantRepository tenantRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final LeadService leadService;

    public OutboundMessageResponse processInboundMessage(InboundMessageRequest request) {
        validateInboundRequest(request);

        Tenant tenant = tenantRepository.findById(request.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", request.getTenantId()));

        List<KnowledgeBase> knowledgeEntries = knowledgeBaseRepository.findByTenantIdAndActiveTrue(tenant.getId());
        String responseMessage = generateResponse(request.getMessage(), knowledgeEntries);

        boolean leadCreated = isLeadIntent(request.getMessage());
        if (leadCreated) {
            leadService.createInternal(
                    tenant.getId(),
                    request.getCustomerPhone(),
                    request.getMessage(),
                    mapChannelToLeadSource(request.getChannel())
            );
        }

        return OutboundMessageResponse.builder()
                .tenantId(tenant.getId())
                .channel(request.getChannel())
                .customerPhone(request.getCustomerPhone())
                .responseMessage(responseMessage)
                .leadCreated(leadCreated)
                .conversationId(null)
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
    }

    private String generateResponse(String message, List<KnowledgeBase> knowledgeEntries) {
        String normalizedMessage = normalize(message);

        for (KnowledgeBase entry : knowledgeEntries) {
            String normalizedQuestion = normalize(entry.getQuestion());
            if (normalizedQuestion.equals(normalizedMessage)
                    || normalizedMessage.contains(normalizedQuestion)
                    || normalizedQuestion.contains(normalizedMessage)) {
                return entry.getAnswer();
            }
        }

        return DEFAULT_REPLY;
    }

    private boolean isLeadIntent(String message) {
        String normalizedMessage = normalize(message);
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

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
    }
}
