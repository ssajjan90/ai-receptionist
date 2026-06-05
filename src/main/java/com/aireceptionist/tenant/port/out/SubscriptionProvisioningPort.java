package com.aireceptionist.tenant.port.out;

import java.util.UUID;

public interface SubscriptionProvisioningPort {

    void provisionBasicSubscription(UUID tenantId);
}
