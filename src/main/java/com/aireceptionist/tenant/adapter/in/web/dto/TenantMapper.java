package com.aireceptionist.tenant.adapter.in.web.dto;

import com.aireceptionist.tenant.domain.BusinessTenant;

public final class TenantMapper {

    private TenantMapper() {
    }

    public static TenantResponse toResponse(BusinessTenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getBusinessName(),
                tenant.getStatus(),
                tenant.getTier(),
                tenant.getCreatedAt()
        );
    }
}
