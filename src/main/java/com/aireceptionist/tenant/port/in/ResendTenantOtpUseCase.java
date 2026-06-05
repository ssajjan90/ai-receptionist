package com.aireceptionist.tenant.port.in;

public interface ResendTenantOtpUseCase {

    void requestNewOtp(String ownerPhone);
}
