package com.aireceptionist.common.event;

/**
 * Story 6.3 (AC1, AC2): a voice caller needs a human. Lives in {@code common.event} (like
 * {@link UnansweredQueryFlaggedEvent}) so the whatsapp module can alert the owner without the
 * voice module ever depending on whatsapp (story 6.4 AC4). The owner transfers by replying
 * TRANSFER (code-review decision) — see {@code common.voice.VoiceTransferPort}.
 */
public class VoiceHumanFallbackRequestedEvent extends AiReceptionistEvent {

    private final String callSid;
    private final String callerPhone;
    private final String ownerPhone;
    private final String reason;

    public VoiceHumanFallbackRequestedEvent(String tenantId, String callSid, String callerPhone,
                                            String ownerPhone, String reason) {
        super(tenantId);
        this.callSid = callSid;
        this.callerPhone = callerPhone;
        this.ownerPhone = ownerPhone;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }
}
