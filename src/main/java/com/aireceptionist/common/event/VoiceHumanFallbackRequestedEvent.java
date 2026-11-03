package com.aireceptionist.common.event;

/**
 * Story 6.3 (AC1, AC2): a voice caller needs a human. Lives in {@code common.event} (like
 * {@link UnansweredQueryFlaggedEvent}) so the whatsapp module can alert the owner without the
 * voice module ever depending on whatsapp (story 6.4 AC4).
 */
public class VoiceHumanFallbackRequestedEvent extends AiReceptionistEvent {

    private final String callSid;
    private final String callerPhone;
    private final String ownerPhone;
    private final String reason;
    private final String transferUrl;

    public VoiceHumanFallbackRequestedEvent(String tenantId, String callSid, String callerPhone,
                                            String ownerPhone, String reason, String transferUrl) {
        super(tenantId);
        this.callSid = callSid;
        this.callerPhone = callerPhone;
        this.ownerPhone = ownerPhone;
        this.reason = reason;
        this.transferUrl = transferUrl;
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

    public String getTransferUrl() {
        return transferUrl;
    }
}
