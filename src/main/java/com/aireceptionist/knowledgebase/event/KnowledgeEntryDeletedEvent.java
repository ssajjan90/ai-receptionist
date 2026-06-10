package com.aireceptionist.knowledgebase.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.util.UUID;

public class KnowledgeEntryDeletedEvent extends AiReceptionistEvent {

    private final String productName;
    private final UUID entryId;

    public KnowledgeEntryDeletedEvent(String tenantId, String productName, UUID entryId) {
        super(tenantId);
        this.productName = productName;
        this.entryId = entryId;
    }

    public String getProductName() { return productName; }
    public UUID getEntryId() { return entryId; }
}
