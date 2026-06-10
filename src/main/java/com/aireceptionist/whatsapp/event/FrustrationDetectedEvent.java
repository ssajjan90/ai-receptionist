package com.aireceptionist.whatsapp.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.util.List;

public class FrustrationDetectedEvent extends AiReceptionistEvent {

    private final String customerPhone;
    private final String triggerMessage;
    private final String ownerPhone;
    private final List<String> conversationHistory;

    public FrustrationDetectedEvent(String tenantId, String customerPhone, String triggerMessage,
                                     String ownerPhone, List<String> conversationHistory) {
        super(tenantId);
        this.customerPhone = customerPhone;
        this.triggerMessage = triggerMessage;
        this.ownerPhone = ownerPhone;
        this.conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getTriggerMessage() {
        return triggerMessage;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public List<String> getConversationHistory() {
        return conversationHistory;
    }
}
