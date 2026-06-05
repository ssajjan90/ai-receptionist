package com.aireceptionist.tenant.port.in;

import com.aireceptionist.tenant.adapter.in.web.dto.AuthResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.VerifyOtpRequest;

public interface VerifyTenantOtpUseCase {

    AuthResponse verifyOtp(VerifyOtpRequest request);
}
