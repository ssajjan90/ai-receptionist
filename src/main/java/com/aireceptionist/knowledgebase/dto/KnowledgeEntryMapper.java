package com.aireceptionist.knowledgebase.dto;

import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeEntryMapper {

    public KnowledgeEntryResponse toResponse(KnowledgeEntry entry) {
        return new KnowledgeEntryResponse(
                entry.getId(),
                entry.getType(),
                entry.getProductName(),
                entry.getQuestion(),
                entry.getAnswer(),
                entry.getPrice(),
                entry.getSource(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }
}
