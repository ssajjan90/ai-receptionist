package com.aireceptionist.knowledgebase.adapter.in.web.dto;

import java.util.List;

public record OcrLowConfidenceError(
        String rawText,
        List<OcrExtractedEntry> partialEntries
) {
}
