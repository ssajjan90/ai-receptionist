package com.aireceptionist.whatsapp.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;

public class OwnerCommandReceivedEvent extends AiReceptionistEvent {

    private final String rawMessage;
    private final String senderPhone;
    private final Instant receivedAt;

    public OwnerCommandReceivedEvent(String tenantId, String rawMessage, String senderPhone, Instant receivedAt) {
        super(tenantId);
        this.rawMessage = rawMessage;
        this.senderPhone = senderPhone;
        this.receivedAt = receivedAt;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public String getSenderPhone() {
        return senderPhone;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
