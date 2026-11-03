package com.aireceptionist.whatsapp.service;

import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.event.OwnerNotificationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Epic 7: delivers owner notifications requested by other modules (billing usage alerts,
 * payment grace/suspension/restoration). Free-form text — same W70 template caveat as every
 * other proactive owner message.
 */
@Service
public class OwnerNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotificationListener.class);

    private final WhatsAppNotificationService notificationService;
    private final TenantOwnerPhonePort ownerPhonePort;

    public OwnerNotificationListener(WhatsAppNotificationService notificationService, TenantOwnerPhonePort ownerPhonePort) {
        this.notificationService = notificationService;
        this.ownerPhonePort = ownerPhonePort;
    }

    @ApplicationModuleListener
    public void onOwnerNotificationRequested(OwnerNotificationRequestedEvent event) {
        String ownerPhone = ownerPhonePort.getOwnerPhone(event.getTenantId()).orElse(null);
        if (ownerPhone == null || ownerPhone.isBlank()) {
            log.warn("Owner notification for tenant {} dropped — no owner phone on record", event.getTenantId());
            return;
        }
        try {
            notificationService.sendMessage(event.getTenantId(), ownerPhone, event.getMessage());
        } catch (Exception ex) {
            log.warn("Failed to send owner notification for tenant={}: {}", event.getTenantId(), ex.getMessage());
        }
    }
}
