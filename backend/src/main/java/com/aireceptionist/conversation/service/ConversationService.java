package com.aireceptionist.conversation.service;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.conversation.entity.Conversation;
import com.aireceptionist.conversation.entity.ConversationMessage;
import com.aireceptionist.conversation.repository.ConversationMessageRepository;
import com.aireceptionist.conversation.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    public Conversation findOrCreateConversation(Long tenantId, CommunicationChannel channel, String customerPhone) {
        if (tenantId == null) {
            throw new BadRequestException("Tenant ID is required.");
        }
        if (channel == null) {
            throw new BadRequestException("Channel is required.");
        }

        return conversationRepository
                .findTopByTenantIdAndChannelAndCustomerPhoneOrderByCreatedAtDesc(tenantId, channel, customerPhone)
                .orElseGet(() -> conversationRepository.save(
                        Conversation.builder()
                                .tenantId(tenantId)
                                .channel(channel)
                                .customerPhone(customerPhone)
                                .build()
                ));
    }

    public ConversationMessage saveInbound(Conversation conversation, String message) {
        return save(conversation, message, ConversationMessage.Direction.INBOUND);
    }

    public ConversationMessage saveOutbound(Conversation conversation, String message) {
        return save(conversation, message, ConversationMessage.Direction.OUTBOUND);
    }

    private ConversationMessage save(Conversation conversation, String message, ConversationMessage.Direction direction) {
        if (conversation == null || conversation.getId() == null) {
            throw new BadRequestException("Conversation is required.");
        }
        if (message == null || message.isBlank()) {
            throw new BadRequestException("Message is required.");
        }

        return conversationMessageRepository.save(
                ConversationMessage.builder()
                        .conversationId(conversation.getId())
                        .direction(direction)
                        .message(message)
                        .build()
        );
    }
}
