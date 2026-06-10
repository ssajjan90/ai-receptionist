package com.aireceptionist.whatsapp.service;

import com.aireceptionist.leads.event.LeadCapturedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
public class LeadCapturedEventListener {

    private static final Logger log = LoggerFactory.getLogger(LeadCapturedEventListener.class);

    private final WhatsAppNotificationService notificationService;

    public LeadCapturedEventListener(WhatsAppNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    void onLeadCaptured(LeadCapturedEvent event) {
        if (event.getOwnerPhone() == null) {
            log.info("Lead captured for tenant {} — no owner phone on record, skipping notification",
                    event.getTenantId());
            return;
        }
        log.info("New lead captured for tenant={}, notifying owner", event.getTenantId());
        String name = event.getCustomerName() != null ? event.getCustomerName() : "Unknown";
        String message = "✅ New lead captured!\nName: " + name +
                "\nPhone: " + event.getCustomerPhone() +
                "\nInterest: " + event.getProductIntent();
        try {
            notificationService.sendMessage(event.getTenantId(), event.getOwnerPhone(), message);
        } catch (Exception ex) {
            log.warn("Failed to notify owner of new lead, tenant={}: {}",
                    event.getTenantId(), ex.getMessage());
        }
    }
}
