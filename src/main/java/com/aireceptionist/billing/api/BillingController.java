package com.aireceptionist.billing.api;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.billing.dto.CheckoutRequest;
import com.aireceptionist.billing.dto.CheckoutResponse;
import com.aireceptionist.billing.dto.SubscriptionStatusResponse;
import com.aireceptionist.billing.service.BillingPlans;
import com.aireceptionist.billing.service.SubscriptionService;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Story 7.1 (AC5) subscription status; story 7.2 (AC1) Razorpay checkout. Owner JWT for own tenant only. */
@RestController
@RequestMapping("/v1/tenants")
public class BillingController extends VersionedRestController {

    private final SubscriptionService subscriptionService;
    private final BillingPlans plans;

    public BillingController(SubscriptionService subscriptionService, BillingPlans plans) {
        this.subscriptionService = subscriptionService;
        this.plans = plans;
    }

    @GetMapping("/{tenantId}/subscription")
    public ApiResponse<SubscriptionStatusResponse> subscription(@PathVariable UUID tenantId, Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        return ApiResponse.ok(subscriptionService.getSubscriptionStatus(tenantId));
    }

    @PostMapping("/{tenantId}/subscription/checkout")
    public ApiResponse<CheckoutResponse> checkout(@PathVariable UUID tenantId,
                                                  @Valid @RequestBody CheckoutRequest request,
                                                  Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        if (plans.tierFor(request.planId()).isEmpty()) {
            throw new ValidationException("INVALID_PLAN", "Unknown subscription plan.");
        }
        return ApiResponse.ok(new CheckoutResponse(subscriptionService.checkout(tenantId, request.planId())));
    }

    private void validateTenantOwnership(UUID tenantId, Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication tenantAuthentication)) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated tenant context is required.");
        }
        if (!tenantId.toString().equals(tenantAuthentication.getTenantId())) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }
    }
}
