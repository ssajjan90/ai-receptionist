package com.aireceptionist.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class FrustrationDetectionService {

    private static final Set<String> FRUSTRATION_KEYWORDS = Set.of(
            "cheated", "wrong", "refund", "complain", "useless", "lied", "fraud", "disappointed"
    );
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
        String lower = text.toLowerCase();
        return FRUSTRATION_KEYWORDS.stream().anyMatch(lower::contains);
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
                .filter(w -> w.equals(w.toUpperCase()) && w.chars().anyMatch(Character::isLetter))
                .count();
        return (double) capsCount / significant.size() >= 0.80;
    }
}
