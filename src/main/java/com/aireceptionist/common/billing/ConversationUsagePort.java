package com.aireceptionist.common.billing;

/**
 * Story 7.3: monthly conversation metering. Implemented by the billing module; called by the
 * WhatsApp pipeline (before AI processing, and after each AI response is sent).
 */
public interface ConversationUsagePort {

    /** AC2: true once a limited (BASIC) tenant has used its monthly allowance. */
    boolean isLimitReached(String tenantId);

    /** AC1/AC3: count one AI conversation and fire 80%/100% alerts when thresholds are crossed. */
    void recordConversation(String tenantId);
}
