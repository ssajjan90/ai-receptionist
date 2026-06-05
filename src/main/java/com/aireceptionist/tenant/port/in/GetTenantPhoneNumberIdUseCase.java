package com.aireceptionist.tenant.port.in;

import java.util.Optional;
import java.util.UUID;

public interface GetTenantPhoneNumberIdUseCase {

    Optional<String> findPhoneNumberId(UUID tenantId);
}
