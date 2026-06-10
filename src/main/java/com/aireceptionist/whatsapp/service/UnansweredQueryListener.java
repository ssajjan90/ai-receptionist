package com.aireceptionist.whatsapp.service;

import com.aireceptionist.knowledgebase.event.UnansweredQueryFlaggedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
public class UnansweredQueryListener {

    private static final Logger log = LoggerFactory.getLogger(UnansweredQueryListener.class);

    private final WhatsAppNotificationService notificationService;

    public UnansweredQueryListener(WhatsAppNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    public void onUnansweredQuery(UnansweredQueryFlaggedEvent event) {
        if (event.getOwnerPhone() == null) {
            log.info("Unanswered query flagged for tenant {} but no owner phone on record — skipping notification",
                    event.getTenantId());
            return;
        }
        log.info("Unanswered query flagged for tenant {} — confidence below threshold", event.getTenantId());
        String message = "⚠️ Unanswered query from " + event.getCustomerPhone()
                + ": '" + event.getOriginalQuery() + "'. Reply to train the AI.";
        try {
            notificationService.sendMessage(event.getTenantId(), event.getOwnerPhone(), message);
        } catch (Exception ex) {
            log.warn("Failed to notify owner for tenant={}: {}", event.getTenantId(), ex.getMessage());
        }
    }
}
