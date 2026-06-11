package com.aireceptionist.knowledgebase.dto;

import com.aireceptionist.knowledgebase.domain.EntryType;
import jakarta.validation.constraints.NotNull;

public record CreateKnowledgeEntryRequest(
        @NotNull EntryType type,
        String productName,
        String question,
        String answer,
        String price
) {}
