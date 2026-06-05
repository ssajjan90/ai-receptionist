package com.aireceptionist.knowledgebase.adapter.in.web.dto;

public record OcrExtractedEntry(
        String productName,
        String price,
        double confidence
) {
}
