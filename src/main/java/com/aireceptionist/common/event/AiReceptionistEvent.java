package com.aireceptionist.common.event;

import java.time.Instant;
import java.util.Objects;

public abstract class AiReceptionistEvent {

    private final String tenantId;
    private final Instant occurredAt;

    protected AiReceptionistEvent(String tenantId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.occurredAt = Instant.now();
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
