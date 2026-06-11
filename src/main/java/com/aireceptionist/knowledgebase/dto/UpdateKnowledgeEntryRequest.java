package com.aireceptionist.knowledgebase.dto;

public record UpdateKnowledgeEntryRequest(
        String productName,
        String question,
        String answer,
        String price
) {}
