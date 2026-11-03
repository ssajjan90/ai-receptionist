package com.aireceptionist.billing.aspect;

import com.aireceptionist.billing.annotation.TierRequired;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Story 7.1 (AC1, AC3): reads the tier from the JWT ({@link TenantAwareAuthentication#getTier()})
 * — never the DB — and rejects lower tiers with {@code TIER_UPGRADE_REQUIRED} (HTTP 403 via
 * {@code GlobalExceptionHandler}'s AuthorizationException mapping).
 */
@Aspect
@Component
public class TierEnforcementAspect {

    public static final String TIER_UPGRADE_REQUIRED = "TIER_UPGRADE_REQUIRED";

    @Around("@annotation(tierRequired)")
    public Object enforce(ProceedingJoinPoint joinPoint, TierRequired tierRequired) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof TenantAwareAuthentication tenantAuth)) {
            // Code review: not a tenant (e.g. admin) — a tier upgrade wouldn't help.
            throw new AuthorizationException("FORBIDDEN", "A tenant account is required for this feature.");
        }
        SubscriptionTier current = SubscriptionTier.parseOrBasic(tenantAuth.getTier());
        if (!current.atLeast(tierRequired.value())) {
            throw new AuthorizationException(TIER_UPGRADE_REQUIRED,
                    "This feature requires the " + tierRequired.value() + " plan. Please upgrade your subscription.");
        }
        return joinPoint.proceed();
    }
}
