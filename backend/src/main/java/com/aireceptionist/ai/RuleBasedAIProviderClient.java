package com.aireceptionist.ai;

import com.aireceptionist.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class RuleBasedAIProviderClient implements AIProviderClient {

    private static final String DEFAULT_REPLY = "Thank you for contacting us. We will get back to you shortly.";

    @Override
    public String generateResponse(AIRequest request) {
        validateRequest(request);

        String customerMessage = normalize(request.getCustomerMessage());
        String knowledgeBase = request.getKnowledgeBase();

        if (knowledgeBase == null || knowledgeBase.isBlank()) {
            return DEFAULT_REPLY;
        }

        return Arrays.stream(knowledgeBase.split("\\R\\R"))
                .map(String::trim)
                .filter(chunk -> !chunk.isBlank())
                .map(this::parseKnowledgeEntry)
                .filter(entry -> entry != null)
                .filter(entry -> matches(customerMessage, entry.question()))
                .map(KnowledgeEntry::answer)
                .findFirst()
                .orElse(DEFAULT_REPLY);
    }

    private void validateRequest(AIRequest request) {
        if (request == null) {
            throw new BadRequestException("AI request is required.");
        }
        if (request.getCustomerMessage() == null || request.getCustomerMessage().isBlank()) {
            throw new BadRequestException("Customer message is required.");
        }
    }

    private KnowledgeEntry parseKnowledgeEntry(String chunk) {
        String[] lines = chunk.split("\\R");
        if (lines.length < 2) {
            return null;
        }

        String question = lines[0].replaceFirst("^Q:\\s*", "").trim();
        String answer = lines[1].replaceFirst("^A:\\s*", "").trim();

        if (question.isBlank() || answer.isBlank()) {
            return null;
        }

        return new KnowledgeEntry(question, answer);
    }

    private boolean matches(String customerMessage, String knowledgeQuestion) {
        String normalizedQuestion = normalize(knowledgeQuestion);

        if (normalizedQuestion.equals(customerMessage)
                || customerMessage.contains(normalizedQuestion)
                || normalizedQuestion.contains(customerMessage)) {
            return true;
        }

        Set<String> customerTokens = Set.of(customerMessage.split("\\s+"));
        Set<String> questionTokens = Set.of(normalizedQuestion.split("\\s+"));
        return customerTokens.stream().anyMatch(questionTokens::contains);
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
    }

    private record KnowledgeEntry(String question, String answer) {
    }
}
