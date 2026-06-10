package com.aireceptionist.whatsapp.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;

public class LeadCaptureRequestedEvent extends AiReceptionistEvent {

    private final String customerPhone;
    private final String customerName;
    private final String capturedPhone;
    private final String productIntent;
    private final String ownerPhone;
    private final Instant consentedAt;

    public LeadCaptureRequestedEvent(String tenantId, String customerPhone, String customerName,
                                     String capturedPhone, String productIntent, String ownerPhone,
                                     Instant consentedAt) {
        super(tenantId);
        this.customerPhone = customerPhone;
        this.customerName = customerName;
        this.capturedPhone = capturedPhone;
        this.productIntent = productIntent;
        this.ownerPhone = ownerPhone;
        this.consentedAt = consentedAt;
    }

    public String getCustomerPhone() { return customerPhone; }
    public String getCustomerName() { return customerName; }
    public String getCapturedPhone() { return capturedPhone; }
    public String getProductIntent() { return productIntent; }
    public String getOwnerPhone() { return ownerPhone; }
    public Instant getConsentedAt() { return consentedAt; }
}
