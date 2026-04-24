package com.aireceptionist.integration.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Placeholder for WhatsApp Business API integration.
 * Wire in the Meta Cloud API / Twilio WhatsApp SDK here.
 */
@Slf4j
@Service
public class WhatsAppService {

    public void sendMessage(String toPhone, String message) {
        log.info("[WhatsApp STUB] To: {} | Message: {}", toPhone, message);
        // TODO: integrate with WhatsApp Business API
    }

    public void sendTemplateMessage(String toPhone, String templateName, Object... params) {
        log.info("[WhatsApp STUB] To: {} | Template: {} | Params: {}", toPhone, templateName, params);
        // TODO: integrate with WhatsApp Business API template messages
    }
}
