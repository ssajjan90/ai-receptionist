package com.aireceptionist.ai.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PromptAssembler {

    private static final String JSON_FORMAT_INSTRUCTION =
            "Always respond ONLY in this JSON format (no markdown, no extra text): " +
            "{\"response\": \"...\", \"confidence\": 0.95, \"language\": \"en\"}";

    public String buildSystemPrompt(String businessName, List<KnowledgeEntry> kbContext, Language language) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the AI receptionist for ").append(businessName).append(". ");
        sb.append("Answer only based on the provided knowledge base. ");
        sb.append("Always respond in ").append(language.toDisplayName()).append(". ");
        sb.append("If you are not confident (less than 75% sure), set confidence below 0.75 in your JSON response.");

        if (language == Language.HINGLISH) {
            sb.append(" The customer may write in Hinglish (mixed Hindi-English). Respond naturally in the same style.");
        }

        if (!kbContext.isEmpty()) {
            sb.append("\n\nKnowledge Base:\n");
            for (KnowledgeEntry entry : kbContext) {
                if (entry.getProductName() != null && entry.getPrice() != null) {
                    sb.append("- ").append(entry.getProductName()).append(": ₹").append(entry.getPrice()).append("\n");
                } else if (entry.getQuestion() != null && entry.getAnswer() != null) {
                    sb.append("Q: ").append(entry.getQuestion()).append("\nA: ").append(entry.getAnswer()).append("\n");
                }
            }
        }

        sb.append("\n\n").append(JSON_FORMAT_INSTRUCTION);
        return sb.toString();
    }

    public String buildUserMessage(List<ConversationTurn> history, String customerMessage) {
        if (history.isEmpty()) {
            return customerMessage;
        }
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : history) {
            sb.append(turn.role()).append(": ").append(turn.content()).append("\n");
        }
        sb.append("customer: ").append(customerMessage);
        return sb.toString();
    }
}
