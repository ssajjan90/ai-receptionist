package com.aireceptionist.tenant.port.in;

import com.aireceptionist.tenant.adapter.in.web.dto.CreateTenantRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.TenantResponse;

public interface RegisterTenantUseCase {

    TenantResponse registerTenant(CreateTenantRequest request);
}
