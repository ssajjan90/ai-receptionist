package com.aireceptionist.knowledgebase.ocr;

public interface OcrProvider {

    String providerName();

    String extractText(byte[] imageBytes, String mimeType);
}
