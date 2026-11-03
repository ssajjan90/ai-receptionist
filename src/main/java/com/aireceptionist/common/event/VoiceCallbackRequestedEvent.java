package com.aireceptionist.common.event;

/**
 * Story 6.3 (AC5): a voice caller asked for a human but the owner didn't transfer within the
 * hold window — the caller should be followed up on WhatsApp.
 */
public class VoiceCallbackRequestedEvent extends AiReceptionistEvent {

    private final String callSid;
    private final String callerPhone;
    private final String ownerPhone;

    public VoiceCallbackRequestedEvent(String tenantId, String callSid, String callerPhone, String ownerPhone) {
        super(tenantId);
        this.callSid = callSid;
        this.callerPhone = callerPhone;
        this.ownerPhone = ownerPhone;
    }

    public String getCallSid() {
        return callSid;
    }

    public String getCallerPhone() {
        return callerPhone;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }
}
