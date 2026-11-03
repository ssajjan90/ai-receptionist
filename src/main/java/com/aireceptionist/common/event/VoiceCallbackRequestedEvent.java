package com.aireceptionist.common.event;

/**
 * Story 6.3 (AC5): a voice caller asked for a human but the owner didn't transfer within the
 * hold window (or hung up while holding) — the caller should be followed up on WhatsApp.
 * {@code callerPhone} is null when the caller ID was withheld/unusable: only the owner is told.
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
