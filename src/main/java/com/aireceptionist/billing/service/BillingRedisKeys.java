package com.aireceptionist.billing.service;

/** Epic 7 Redis keys (story-specified names where the stories give one). */
final class BillingRedisKeys {

    private BillingRedisKeys() {
    }

    /** Story 7.3 (AC3): monthly AI-conversation counter. */
    static String conversationCount(String tenantId) {
        return "tenant:" + tenantId + ":conv:month";
    }

    /** Story 7.3 (AC2): set at 100% — WhatsApp replies "service limit reached" instead of running AI. */
    static String limitReached(String tenantId) {
        return "tenant:" + tenantId + ":conv:limit_reached";
    }

    static String alert80(String tenantId) {
        return "tenant:" + tenantId + ":alert:80pct";
    }

    static String alert100(String tenantId) {
        return "tenant:" + tenantId + ":alert:100pct";
    }

    /** Story 7.4 (Task 1): once-only grace notifications. */
    static String graceStartNotified(String tenantId) {
        return "grace:notified:start:" + tenantId;
    }

    static String grace24hNotified(String tenantId) {
        return "grace:notified:24h:" + tenantId;
    }

    /** Story 7.2 (AC6): processed Razorpay event ids. */
    static String razorpayEvent(String eventId) {
        return "razorpay:event:" + eventId;
    }
}
