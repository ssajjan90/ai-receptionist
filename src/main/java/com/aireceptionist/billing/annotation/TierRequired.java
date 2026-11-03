package com.aireceptionist.billing.annotation;

import com.aireceptionist.billing.domain.SubscriptionTier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Story 7.1 (AC1-AC3): the authenticated tenant's JWT {@code tier} claim must rank at least
 * {@link #value()}; otherwise HTTP 403 {@code TIER_UPGRADE_REQUIRED}. No DB call per request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TierRequired {

    SubscriptionTier value();
}
