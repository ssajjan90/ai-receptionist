package com.aireceptionist.knowledgebase.service;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrExtractedEntry;
import com.aireceptionist.knowledgebase.exception.OcrLowConfidenceException;
import com.aireceptionist.knowledgebase.ocr.OcrProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Service
public class OcrIngestionService {

    private static final Pattern PRICE_LINE = Pattern.compile(
            "^(.+?)[\\s\\-–:]+(?:₹|Rs\\.?\\s*)?(\\d[\\d,]*(?:\\.\\d{1,2})?)(?:/-)?$",
            Pattern.CASE_INSENSITIVE
    );

    private final Map<String, OcrProvider> providers;
    private final String providerName;

    public OcrIngestionService(List<OcrProvider> providers,
                               @Value("${app.ocr.provider:google}") String providerName) {
        this.providers = new LinkedHashMap<>();
        providers.forEach(provider -> this.providers.put(provider.providerName().toLowerCase(Locale.ROOT), provider));
        this.providerName = providerName.toLowerCase(Locale.ROOT);
    }

    @Async
    public CompletableFuture<String> extractFromImage(byte[] imageBytes, String mimeType) {
        OcrProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new ExternalServiceException("Unsupported OCR provider: " + providerName);
        }
        return CompletableFuture.completedFuture(provider.extractText(imageBytes, mimeType));
    }

    public List<OcrExtractedEntry> parseProductPrices(String rawText) {
        List<String> nonEmptyLines = rawText == null
                ? List.of()
                : rawText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();

        int matchedCount = 0;
        Map<String, OcrExtractedEntry> entriesByProduct = new LinkedHashMap<>();
        for (String line : nonEmptyLines) {
            var matcher = PRICE_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String productName = normalizeProductName(matcher.group(1));
            String price = normalizePrice(matcher.group(2));
            if (productName.length() < 2) {
                continue;
            }
            matchedCount++;
            entriesByProduct.put(productName.toLowerCase(Locale.ROOT), new OcrExtractedEntry(productName, price, 0.0));
        }
        double confidence = nonEmptyLines.isEmpty() ? 0.0 : (double) matchedCount / nonEmptyLines.size();
        entriesByProduct.replaceAll((key, entry) -> new OcrExtractedEntry(entry.productName(), entry.price(), confidence));

        List<OcrExtractedEntry> entries = new ArrayList<>(entriesByProduct.values());
        entries.sort(Comparator.comparing(OcrExtractedEntry::productName));
        if (entries.size() < 2) {
            throw new OcrLowConfidenceException(rawText == null ? "" : rawText, entries);
        }
        return entries;
    }

    private String normalizeProductName(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String normalizePrice(String value) {
        return value.replace(",", "").trim();
    }
}
