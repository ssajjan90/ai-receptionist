package com.aireceptionist.tenant.domain;

public enum TenantStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    ONBOARDING_COMPLETE,
    LIVE,
    SUSPENDED,
    PAYMENT_SUSPENDED,
    TERMINATED,
    ERASED
}
