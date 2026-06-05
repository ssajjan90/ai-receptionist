package com.aireceptionist.knowledgebase.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.util.UUID;

public class KnowledgeEntryAddedEvent extends AiReceptionistEvent {

    private final int entryCount;

    public KnowledgeEntryAddedEvent(UUID tenantId, int entryCount) {
        super(tenantId.toString());
        this.entryCount = entryCount;
    }

    public int getEntryCount() {
        return entryCount;
    }
}
