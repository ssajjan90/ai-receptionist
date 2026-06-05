package com.aireceptionist.whatsapp.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;
import java.util.UUID;

public class InboundWhatsAppMessageEvent extends AiReceptionistEvent {

    private final UUID tenantId;
    private final String messageId;
    private final String senderPhone;
    private final String messageText;
    private final String phoneNumberId;
    private final Instant receivedAt;

    public InboundWhatsAppMessageEvent(UUID tenantId, String messageId, String senderPhone,
                                       String messageText, String phoneNumberId, Instant receivedAt) {
        super(tenantId.toString());
        this.tenantId = tenantId;
        this.messageId = messageId;
        this.senderPhone = senderPhone;
        this.messageText = messageText;
        this.phoneNumberId = phoneNumberId;
        this.receivedAt = receivedAt;
    }

    public UUID getTenantIdValue() {
        return tenantId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSenderPhone() {
        return senderPhone;
    }

    public String getMessageText() {
        return messageText;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
