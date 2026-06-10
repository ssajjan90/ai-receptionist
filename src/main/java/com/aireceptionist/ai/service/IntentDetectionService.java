package com.aireceptionist.ai.service;

import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class IntentDetectionService {

    static final Set<String> PURCHASE_INTENT_SIGNALS = Set.of(
            "buy", "price", "available", "book", "reserve", "interested", "how much",
            "kitna", "kharidna", "chahiye"
    );

    private static final Pattern DECLINE_PATTERN = Pattern.compile(
            "\\b(?:no|nahi|nope|skip)\\b|\\bno thanks\\b|\\bnot interested\\b",
            Pattern.CASE_INSENSITIVE);

    public boolean hasPurchaseIntent(String messageText) {
        if (messageText == null || messageText.isBlank()) return false;
        String lower = messageText.toLowerCase();
        return PURCHASE_INTENT_SIGNALS.stream().anyMatch(lower::contains);
    }

    public boolean isDecline(String messageText) {
        if (messageText == null || messageText.isBlank()) return false;
        return DECLINE_PATTERN.matcher(messageText).find();
    }

    public Optional<String> extractProductFromMessage(String messageText, List<KnowledgeEntry> kbContext) {
        if (messageText == null || messageText.isBlank()) return Optional.empty();
        String lower = messageText.toLowerCase();
        return kbContext.stream()
                .filter(e -> e.getProductName() != null)
                .filter(e -> lower.contains(e.getProductName().toLowerCase()))
                .map(KnowledgeEntry::getProductName)
                .findFirst();
    }
}
