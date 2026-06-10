package com.aireceptionist.knowledgebase.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

public class UnansweredQueryFlaggedEvent extends AiReceptionistEvent {

    private final String customerPhone;
    private final String originalQuery;
    private final String ownerPhone;

    public UnansweredQueryFlaggedEvent(String tenantId, String customerPhone,
                                       String originalQuery, String ownerPhone) {
        super(tenantId);
        this.customerPhone = customerPhone;
        this.originalQuery = originalQuery;
        this.ownerPhone = ownerPhone;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }
}
