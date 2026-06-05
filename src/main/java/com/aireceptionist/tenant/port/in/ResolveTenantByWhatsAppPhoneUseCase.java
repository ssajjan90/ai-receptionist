package com.aireceptionist.tenant.port.in;

import java.util.Optional;

public interface ResolveTenantByWhatsAppPhoneUseCase {

    Optional<ResolvedTenantWhatsAppRoute> resolveByPhoneNumberId(String phoneNumberId);
}
