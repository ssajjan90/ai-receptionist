package com.aireceptionist.knowledgebase.adapter.in.web.dto;

import java.util.List;

public record OcrImportResponse(
        List<OcrExtractedEntry> extractedEntries,
        String rawText,
        long processingTimeMs
) {
}
