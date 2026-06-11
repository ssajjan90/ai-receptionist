package com.aireceptionist.knowledgebase.dto;

import com.aireceptionist.knowledgebase.domain.EntryType;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeEntryResponse(
        UUID id,
        EntryType type,
        String productName,
        String question,
        String answer,
        String price,
        String source,
        Instant createdAt,
        Instant updatedAt
) {}
