package com.aireceptionist.tenant.port.out;

public interface OwnerNotificationPort {

    void sendOtp(String phone, String otp);
}
