package com.aireceptionist.tenant.port.in;

import java.util.List;
import java.util.UUID;

public interface GetLiveTenantsUseCase {

    List<UUID> getLiveTenantIds();
}
