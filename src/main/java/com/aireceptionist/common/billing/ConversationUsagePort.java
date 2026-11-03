package com.aireceptionist.common.billing;

/**
 * Story 7.3: monthly conversation metering, implemented by billing and called by the WhatsApp
 * pipeline. A conversation is one customer phone number per day (code-review decision).
 */
public interface ConversationUsagePort {

    /** AC2: true when the tenant's monthly allowance is used up and this customer isn't already being served today. */
    boolean isLimitReached(String tenantId, String customerPhone);

    /** AC1/AC3: count this customer's conversation (once per day) after a reply is actually delivered. */
    void recordConversation(String tenantId, String customerPhone);
}
