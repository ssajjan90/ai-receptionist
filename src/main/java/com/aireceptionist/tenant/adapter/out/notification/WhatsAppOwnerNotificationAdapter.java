package com.aireceptionist.tenant.adapter.out.notification;

import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppOwnerNotificationAdapter implements OwnerNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOwnerNotificationAdapter.class);

    @Override
    public void sendOtp(String phone, String otp) {
        log.info("Sending OTP {} to {}", otp, phone);
    }
}
