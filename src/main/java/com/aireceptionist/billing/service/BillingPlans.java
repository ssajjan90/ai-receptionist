package com.aireceptionist.billing.service;

import com.aireceptionist.billing.domain.SubscriptionTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Story 7.2: Razorpay plan id ↔ tier, from {@code app.razorpay.plans.*}. */
@Component
public class BillingPlans {

    private final Map<String, SubscriptionTier> tierByPlanId = new LinkedHashMap<>();

    public BillingPlans(@Value("${app.razorpay.plans.basic:}") String basicPlanId,
                        @Value("${app.razorpay.plans.pro:}") String proPlanId,
                        @Value("${app.razorpay.plans.enterprise:}") String enterprisePlanId) {
        register(basicPlanId, SubscriptionTier.BASIC);
        register(proPlanId, SubscriptionTier.PRO);
        register(enterprisePlanId, SubscriptionTier.ENTERPRISE);
    }

    private void register(String planId, SubscriptionTier tier) {
        if (planId != null && !planId.isBlank()) {
            tierByPlanId.put(planId.trim(), tier);
        }
    }

    public Optional<SubscriptionTier> tierFor(String planId) {
        return planId == null ? Optional.empty() : Optional.ofNullable(tierByPlanId.get(planId.trim()));
    }
}
