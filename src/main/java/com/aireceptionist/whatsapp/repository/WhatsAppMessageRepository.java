package com.aireceptionist.whatsapp.repository;

import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, UUID> {

    List<WhatsAppMessage> findByTenantIdOrderByReceivedAtDesc(UUID tenantId, Pageable pageable);
}
