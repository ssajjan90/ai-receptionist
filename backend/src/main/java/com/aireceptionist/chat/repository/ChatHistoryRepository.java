package com.aireceptionist.chat.repository;

import com.aireceptionist.chat.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    List<ChatHistory> findByTenantIdAndCustomerPhoneOrderByCreatedAtAsc(Long tenantId, String customerPhone);

    List<ChatHistory> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
