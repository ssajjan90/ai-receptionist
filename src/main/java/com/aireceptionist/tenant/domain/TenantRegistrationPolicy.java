package com.aireceptionist.tenant.domain;

import com.aireceptionist.common.exception.BusinessRuleException;

public final class TenantRegistrationPolicy {

    private TenantRegistrationPolicy() {
    }

    public static void ensureDedicatedBusinessPhone(String ownerPhone, String businessPhone) {
        if (ownerPhone.equals(businessPhone)) {
            throw new BusinessRuleException(
                    "SAME_PHONE_CONFLICT",
                    "Use a dedicated business WhatsApp number that differs from the owner's personal phone.");
        }
    }
}
