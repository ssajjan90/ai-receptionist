package com.aireceptionist.tenant.port.out;

public interface OtpPort {

    String generateAndStore(String phone);

    void verifyAndConsume(String phone, String otp);

    void invalidateExisting(String phone);
}
