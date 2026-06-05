package com.aireceptionist.tenant.port.in;

import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingResponse;

import java.util.UUID;

public interface CompleteTenantOnboardingUseCase {

    OnboardingResponse completeOnboarding(UUID tenantId, OnboardingRequest request);
}
