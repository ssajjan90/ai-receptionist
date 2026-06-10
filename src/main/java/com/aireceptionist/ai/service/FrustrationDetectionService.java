package com.aireceptionist.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class FrustrationDetectionService {

    private static final Pattern FRUSTRATION_KEYWORD_PATTERN = Pattern.compile(
            "\\b(?:cheated|wrong|refund|complain|useless|lied|fraud|disappointed)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("[!?]{3,}");

    public boolean isFrustrated(String messageText) {
        if (messageText == null || messageText.isBlank()) return false;
        return hasKeyword(messageText) || hasRepeatedPunctuation(messageText) || isAllCaps(messageText);
    }

    public List<String> getFrustrationSignals(String messageText) {
        List<String> signals = new ArrayList<>();
        if (messageText == null || messageText.isBlank()) return signals;
        if (hasKeyword(messageText)) signals.add("keyword");
        if (hasRepeatedPunctuation(messageText)) signals.add("punctuation");
        if (isAllCaps(messageText)) signals.add("all-caps");
        return signals;
    }

    private boolean hasKeyword(String text) {
        return FRUSTRATION_KEYWORD_PATTERN.matcher(text).find();
    }

    private boolean hasRepeatedPunctuation(String text) {
        return REPEATED_PUNCTUATION.matcher(text).find();
    }

    private boolean isAllCaps(String text) {
        List<String> significant = Arrays.stream(text.split("\\s+"))
                .filter(w -> w.length() >= 2)
                .toList();
        if (significant.size() < 5) return false;
        long capsCount = significant.stream()
                .filter(w -> w.equals(w.toUpperCase()) && w.chars().anyMatch(c -> c < 128 && Character.isLetter((char) c)))
                .count();
        return (double) capsCount / significant.size() >= 0.80;
    }
}
