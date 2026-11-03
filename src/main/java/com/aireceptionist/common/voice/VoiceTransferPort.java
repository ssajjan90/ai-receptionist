package com.aireceptionist.common.voice;

/**
 * Story 6.3 (review decision): the owner replies TRANSFER on WhatsApp to bridge the caller who is
 * currently on hold. Implemented by the voice module; called by whatsapp's owner-command parser.
 */
public interface VoiceTransferPort {

    enum Outcome { TRANSFERRED, NO_CALL_WAITING, FAILED }

    /** Caller must have set TenantContext to {@code tenantId}. */
    Outcome transferWaitingCall(String tenantId, String ownerPhone);
}
