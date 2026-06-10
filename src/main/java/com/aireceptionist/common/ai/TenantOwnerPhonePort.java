package com.aireceptionist.common.ai;

import java.util.Optional;

public interface TenantOwnerPhonePort {

    Optional<String> getOwnerPhone(String tenantId);
}
