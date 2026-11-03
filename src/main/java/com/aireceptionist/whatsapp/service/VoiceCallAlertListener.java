package com.aireceptionist.whatsapp.service;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.event.VoiceCallbackRequestedEvent;
import com.aireceptionist.common.event.VoiceHumanFallbackRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Story 6.3: WhatsApp side of the voice human-fallback flow. Listens to {@code common.event}
 * events only, so voice never depends on whatsapp (story 6.4 AC4).
 *
 * <p>Free-form text is used, like every other owner alert here — outside Meta's 24h session
 * window these need an approved template (W70), which also applies to the caller follow-up.
 */
@Service
public class VoiceCallAlertListener {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallAlertListener.class);

    private final WhatsAppNotificationService notificationService;
    private final TenantNamePort tenantNamePort;

    public VoiceCallAlertListener(WhatsAppNotificationService notificationService, TenantNamePort tenantNamePort) {
        this.notificationService = notificationService;
        this.tenantNamePort = tenantNamePort;
    }

    /** AC1, AC2: real-time owner alert with caller, call SID and the transfer action link. */
    @ApplicationModuleListener
    public void onHumanFallbackRequested(VoiceHumanFallbackRequestedEvent event) {
        if (event.getOwnerPhone() == null) {
            log.warn("Voice caller needs a human for tenant {} but no owner phone on record", event.getTenantId());
            return;
        }
        String message = "📞 A caller needs to speak to you now!\n"
                + "Caller: " + sanitize(event.getCallerPhone()) + "\n"
                + "Call SID: " + sanitize(event.getCallSid()) + "\n"
                + "Reason: " + sanitize(event.getReason()) + "\n\n"
                + "They are on hold for 60 seconds. Transfer Call: " + event.getTransferUrl();
        send(event.getTenantId(), event.getOwnerPhone(), message);
    }

    /** AC5: owner didn't pick up — follow up with the caller (and tell the owner). */
    @ApplicationModuleListener
    public void onCallbackRequested(VoiceCallbackRequestedEvent event) {
        String businessName = tenantNamePort.getBusinessName(event.getTenantId()).orElse("us");
        send(event.getTenantId(), event.getCallerPhone(),
                "Hi! Sorry we couldn't connect you to our team when you called " + businessName
                        + ". Someone will get back to you here shortly — feel free to type your question.");
        if (event.getOwnerPhone() != null) {
            send(event.getTenantId(), event.getOwnerPhone(),
                    "⏰ Missed transfer: caller " + sanitize(event.getCallerPhone())
                            + " waited but wasn't connected. We've told them you'll follow up on WhatsApp.");
        }
    }

    private void send(String tenantId, String phone, String message) {
        try {
            notificationService.sendMessage(tenantId, phone, message);
        } catch (Exception ex) {
            log.warn("Failed to send voice alert for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\n\r\t]", " ");
    }
}
