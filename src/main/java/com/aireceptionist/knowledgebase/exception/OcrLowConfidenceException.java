package com.aireceptionist.knowledgebase.exception;

import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrExtractedEntry;

import java.util.List;

public class OcrLowConfidenceException extends BusinessRuleException {

    private final String rawText;
    private final List<OcrExtractedEntry> partialEntries;

    public OcrLowConfidenceException(String rawText, List<OcrExtractedEntry> partialEntries) {
        super("OCR_LOW_CONFIDENCE", "OCR extraction produced too few parseable entries.");
        this.rawText = rawText;
        this.partialEntries = List.copyOf(partialEntries);
    }

    public String getRawText() {
        return rawText;
    }

    public List<OcrExtractedEntry> getPartialEntries() {
        return partialEntries;
    }
}
