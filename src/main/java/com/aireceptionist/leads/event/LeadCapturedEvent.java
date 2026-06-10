package com.aireceptionist.leads.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.util.UUID;

public class LeadCapturedEvent extends AiReceptionistEvent {

    private final UUID leadId;
    private final String customerName;
    private final String customerPhone;
    private final String productIntent;
    private final String ownerPhone;

    public LeadCapturedEvent(String tenantId, UUID leadId, String customerName, String customerPhone,
                              String productIntent, String ownerPhone) {
        super(tenantId);
        this.leadId = leadId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.productIntent = productIntent;
        this.ownerPhone = ownerPhone;
    }

    public UUID getLeadId() { return leadId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public String getProductIntent() { return productIntent; }
    public String getOwnerPhone() { return ownerPhone; }
}
