package com.aireceptionist.tenant.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.util.UUID;

public class TenantOnboardedEvent extends AiReceptionistEvent {

    private final UUID tenantId;
    private final String businessName;

    public TenantOnboardedEvent(UUID tenantId, String businessName) {
        super(tenantId.toString());
        this.tenantId = tenantId;
        this.businessName = businessName;
    }

    public UUID getTenantIdValue() {
        return tenantId;
    }

    public String getBusinessName() {
        return businessName;
    }
}
