package com.aireceptionist.common.ai;

import java.util.Optional;

public interface TenantNamePort {

    Optional<String> getBusinessName(String tenantId);
}
