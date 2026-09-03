package com.aireceptionist.tenant.port.in;

import java.util.Optional;

/**
 * Story 6.1 (AC2). Dev Notes named a {@code TenantService.findByBusinessPhone()} that doesn't
 * exist in this codebase — added following the same shape as the already-established
 * {@link ResolveTenantByWhatsAppPhoneUseCase}, but keyed on the tenant's registered business
 * phone number ({@code tenants.phone_number}, the Exotel {@code To} field) rather than the
 * WhatsApp-specific {@code phone_number_id}.
 */
public interface ResolveTenantByBusinessPhoneUseCase {

    Optional<ResolvedTenantVoiceRoute> resolveByBusinessPhone(String businessPhone);
}
