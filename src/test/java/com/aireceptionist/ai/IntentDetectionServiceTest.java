package com.aireceptionist.ai;

import com.aireceptionist.ai.service.IntentDetectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDetectionServiceTest {

    private final IntentDetectionService service = new IntentDetectionService();

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to buy a phone",
            "What is the price of Samsung?",
            "Is this available?",
            "Can I book a table?",
            "I want to reserve a spot",
            "I am interested in this",
            "How much does it cost?",
            "kitna paisa hai",
            "mujhe kharidna hai",
            "ye mujhe chahiye"
    })
    void detectsPurchaseIntent(String message) {
        assertThat(service.hasPurchaseIntent(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Hello, how are you?",
            "Thank you for your help",
            "What are your opening hours?",
            "Tell me about your services",
            "Good morning"
    })
    void noFalsePositivesOnGeneralQueries(String message) {
        assertThat(service.hasPurchaseIntent(message)).isFalse();
    }

    @Test
    void caseInsensitiveDetection() {
        assertThat(service.hasPurchaseIntent("WHAT IS THE PRICE?")).isTrue();
        assertThat(service.hasPurchaseIntent("Buy Now")).isTrue();
    }

    @Test
    void nullAndBlankReturnFalse() {
        assertThat(service.hasPurchaseIntent(null)).isFalse();
        assertThat(service.hasPurchaseIntent("   ")).isFalse();
    }

    @Test
    void detectsDeclineKeywords() {
        assertThat(service.isDecline("no thanks")).isTrue();
        assertThat(service.isDecline("nahi")).isTrue();
        assertThat(service.isDecline("not interested")).isTrue();
        assertThat(service.isDecline("skip")).isTrue();
    }

    @Test
    void extractsProductFromKbContext() {
        var entries = List.of(
                knowledgeEntry("Samsung Galaxy S24"),
                knowledgeEntry("iPhone 15")
        );
        var result = service.extractProductFromMessage("I want to buy Samsung Galaxy S24", entries);
        assertThat(result).contains("Samsung Galaxy S24");
    }

    @Test
    void returnsEmptyWhenNoProductMatch() {
        var entries = List.of(knowledgeEntry("Samsung Galaxy S24"));
        var result = service.extractProductFromMessage("tell me about your services", entries);
        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyKbContext() {
        var result = service.extractProductFromMessage("buy something", List.of());
        assertThat(result).isEmpty();
    }

    private com.aireceptionist.knowledgebase.domain.KnowledgeEntry knowledgeEntry(String productName) {
        return com.aireceptionist.knowledgebase.domain.KnowledgeEntry.product(
                java.util.UUID.randomUUID(), productName, "9999", "TEST");
    }
}
