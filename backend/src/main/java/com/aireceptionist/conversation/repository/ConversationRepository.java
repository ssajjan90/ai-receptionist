package com.aireceptionist.conversation.repository;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<Conversation> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Conversation> findTopByTenantIdAndChannelAndCustomerPhoneOrderByCreatedAtDesc(
            Long tenantId,
            CommunicationChannel channel,
            String customerPhone
    );
}
