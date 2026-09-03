package com.aireceptionist.voice.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

public class VoiceCallReceivedEvent extends AiReceptionistEvent {

    private final String callSid;
    private final String callerPhone;
    private final String businessPhone;

    public VoiceCallReceivedEvent(String tenantId, String callSid, String callerPhone, String businessPhone) {
        super(tenantId);
        this.callSid = callSid;
        this.callerPhone = callerPhone;
        this.businessPhone = businessPhone;
    }

    public String getCallSid() {
        return callSid;
    }

    public String getCallerPhone() {
        return callerPhone;
    }

    public String getBusinessPhone() {
        return businessPhone;
    }
}
