package com.aireceptionist.conversation.repository;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findTopByTenantIdAndChannelAndCustomerPhoneOrderByCreatedAtDesc(
            Long tenantId,
            CommunicationChannel channel,
            String customerPhone
    );
}
