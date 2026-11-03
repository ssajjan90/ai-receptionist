package com.aireceptionist.common.event;

/**
 * Epic 7: a module wants the tenant owner told something on WhatsApp (usage alerts, payment
 * grace/suspension/restoration). Lives in {@code common.event} so billing never depends on
 * whatsapp (which itself calls billing for usage counting — a direct dependency would be a cycle).
 */
public class OwnerNotificationRequestedEvent extends AiReceptionistEvent {

    private final String message;

    public OwnerNotificationRequestedEvent(String tenantId, String message) {
        super(tenantId);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
