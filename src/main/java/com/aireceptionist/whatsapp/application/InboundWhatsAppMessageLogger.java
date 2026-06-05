package com.aireceptionist.whatsapp.application;

import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class InboundWhatsAppMessageLogger {

    private static final Logger log = LoggerFactory.getLogger(InboundWhatsAppMessageLogger.class);

    @ApplicationModuleListener
    void onInboundWhatsAppMessage(InboundWhatsAppMessageEvent event) {
        log.debug(
                "Inbound WhatsApp message accepted for tenantId={}, phoneNumberId={}, messageId={}",
                event.getTenantIdValue(),
                event.getPhoneNumberId(),
                event.getMessageId()
        );
    }
}
