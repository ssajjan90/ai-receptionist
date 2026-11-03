package com.aireceptionist.billing.domain;

/** Story 7.1. Rank BASIC=1 < PRO=2 < ENTERPRISE=3; monthly conversation limits from the PRD tier table. */
public enum SubscriptionTier {
    BASIC(1, 300),
    PRO(2, null),
    ENTERPRISE(3, null);

    private final int rank;
    private final Integer conversationLimit;

    SubscriptionTier(int rank, Integer conversationLimit) {
        this.rank = rank;
        this.conversationLimit = conversationLimit;
    }

    public int rank() {
        return rank;
    }

    /** {@code null} = unlimited. */
    public Integer conversationLimit() {
        return conversationLimit;
    }

    public boolean atLeast(SubscriptionTier required) {
        return rank >= required.rank;
    }

    /** Unknown/missing claims are treated as the lowest tier. */
    public static SubscriptionTier parseOrBasic(String value) {
        if (value == null) {
            return BASIC;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BASIC;
        }
    }
}
