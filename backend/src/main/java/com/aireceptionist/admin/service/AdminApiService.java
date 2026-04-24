package com.aireceptionist.admin.service;

import com.aireceptionist.admin.dto.AdminConversationMessageResponse;
import com.aireceptionist.admin.dto.AdminConversationResponse;
import com.aireceptionist.auth.security.AdminAccessService;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.conversation.entity.Conversation;
import com.aireceptionist.conversation.entity.ConversationMessage;
import com.aireceptionist.conversation.repository.ConversationMessageRepository;
import com.aireceptionist.conversation.repository.ConversationRepository;
import com.aireceptionist.lead.dto.LeadResponse;
import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminApiService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final LeadRepository leadRepository;
    private final AdminAccessService adminAccessService;

    public List<AdminConversationResponse> getConversationsByTenant(Long tenantId) {
        adminAccessService.validateTenantAccess(tenantId);

        return conversationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toConversationResponseWithoutMessages)
                .toList();
    }

    public AdminConversationResponse getConversationById(Long id) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));

        adminAccessService.validateTenantAccess(conversation.getTenantId());

        List<AdminConversationMessageResponse> messages = conversationMessageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(this::toConversationMessageResponse)
                .toList();

        return toConversationResponse(conversation, messages);
    }

    public List<LeadResponse> getLeadsByTenant(Long tenantId) {
        adminAccessService.validateTenantAccess(tenantId);
        return leadRepository.findByTenantId(tenantId).stream()
                .map(this::toLeadResponse)
                .toList();
    }

    private AdminConversationResponse toConversationResponseWithoutMessages(Conversation conversation) {
        return toConversationResponse(conversation, List.of());
    }

    private AdminConversationResponse toConversationResponse(
            Conversation conversation,
            List<AdminConversationMessageResponse> messages
    ) {
        return AdminConversationResponse.builder()
                .id(conversation.getId())
                .tenantId(conversation.getTenantId())
                .channel(conversation.getChannel())
                .customerPhone(conversation.getCustomerPhone())
                .createdAt(conversation.getCreatedAt())
                .messages(messages)
                .build();
    }

    private AdminConversationMessageResponse toConversationMessageResponse(ConversationMessage message) {
        return AdminConversationMessageResponse.builder()
                .id(message.getId())
                .direction(message.getDirection())
                .message(message.getMessage())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private LeadResponse toLeadResponse(Lead lead) {
        return LeadResponse.builder()
                .id(lead.getId())
                .tenantId(lead.getTenantId())
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .email(lead.getEmail())
                .requirement(lead.getRequirement())
                .status(lead.getStatus())
                .source(lead.getSource())
                .createdAt(lead.getCreatedAt())
                .updatedAt(lead.getUpdatedAt())
                .build();
    }
}
