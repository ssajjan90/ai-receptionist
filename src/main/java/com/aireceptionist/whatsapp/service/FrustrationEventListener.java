package com.aireceptionist.whatsapp.service;

import com.aireceptionist.whatsapp.event.FrustrationDetectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FrustrationEventListener {

    private static final Logger log = LoggerFactory.getLogger(FrustrationEventListener.class);

    private final WhatsAppNotificationService notificationService;

    public FrustrationEventListener(WhatsAppNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\n\r\t]", " ");
    }

    @ApplicationModuleListener
    void onFrustrationDetected(FrustrationDetectedEvent event) {
        if (event.getOwnerPhone() == null) {
            log.info("Frustrated customer in tenant {} — no owner phone on record, skipping notification",
                    event.getTenantId());
            return;
        }
        log.info("Frustrated customer alert for tenant {}", event.getTenantId());

        List<String> history = event.getConversationHistory();
        String lastTurns = history.stream()
                .skip(Math.max(0, history.size() - 3))
                .collect(Collectors.joining("\n"));

        String message = "🚨 Frustrated customer alert!\n"
                + "Customer: " + sanitize(event.getCustomerPhone()) + "\n"
                + "Message: \"" + sanitize(event.getTriggerMessage()) + "\"\n\n"
                + "Recent conversation:\n" + lastTurns;

        try {
            notificationService.sendMessage(event.getTenantId(), event.getOwnerPhone(), message);
        } catch (Exception ex) {
            log.warn("Failed to notify owner of frustrated customer, tenant={}: {}",
                    event.getTenantId(), ex.getMessage());
        }
    }
}
