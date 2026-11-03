package com.aireceptionist.billing.dto;

import java.time.Instant;

/** Story 7.1 (AC5). {@code conversationLimit} null = unlimited. */
public record SubscriptionStatusResponse(String tier, String status, int conversationUsedThisMonth,
                                         Integer conversationLimit, Instant nextBillingDate) {
}
