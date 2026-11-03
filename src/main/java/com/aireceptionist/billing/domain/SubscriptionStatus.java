package com.aireceptionist.billing.domain;

/** Story 7.1. Matches {@code chk_subscriptions_status}. */
public enum SubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    CANCELLED
}
