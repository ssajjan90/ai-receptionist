package com.aireceptionist.ai;

import com.aireceptionist.ai.service.FrustrationDetectionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrustrationDetectionServiceTest {

    private final FrustrationDetectionService service = new FrustrationDetectionService();

    // ── Keyword detection ────────────────────────────────────────────────────

    @Test
    void keywordCheatedIsFrustrated() {
        assertThat(service.isFrustrated("This product cheated me!")).isTrue();
    }

    @Test
    void keywordRefundIsFrustrated() {
        assertThat(service.isFrustrated("I need a refund immediately")).isTrue();
    }

    @Test
    void keywordFraudIsFrustrated() {
        assertThat(service.isFrustrated("This is complete fraud")).isTrue();
    }

    @Test
    void keywordDisappointedIsFrustrated() {
        assertThat(service.isFrustrated("I am very disappointed with your service")).isTrue();
    }

    @Test
    void hinglishKeywordRefundIsFrustrated() {
        assertThat(service.isFrustrated("Refund karo please!!!")).isTrue();
    }

    // ── Punctuation detection ────────────────────────────────────────────────

    @Test
    void tripleBangIsFrustrated() {
        assertThat(service.isFrustrated("Why isn't it working???")).isTrue();
    }

    @Test
    void fourBangsIsFrustrated() {
        assertThat(service.isFrustrated("Unbelievable!!!!")).isTrue();
    }

    @Test
    void doubleBangIsNotFrustrated() {
        assertThat(service.isFrustrated("Wow that's great!!")).isFalse();
    }

    // ── ALL CAPS detection ───────────────────────────────────────────────────

    @Test
    void allCapsWithFiveWordsIsFrustrated() {
        assertThat(service.isFrustrated("STOP EATING ALL THE DONUTS TODAY")).isTrue();
    }

    @Test
    void allCapsWithFourWordsIsNotFrustrated() {
        assertThat(service.isFrustrated("THIS IS VERY BAD")).isFalse();
    }

    @Test
    void mixedCapsBelow80PercentIsNotFrustrated() {
        assertThat(service.isFrustrated("THIS is VERY BAD situation here")).isFalse();
    }

    // ── Normal messages ──────────────────────────────────────────────────────

    @Test
    void normalMessageIsNotFrustrated() {
        assertThat(service.isFrustrated("What is the price of iPhone?")).isFalse();
    }

    @Test
    void emptyMessageIsNotFrustrated() {
        assertThat(service.isFrustrated("")).isFalse();
        assertThat(service.isFrustrated(null)).isFalse();
    }

    @Test
    void greetingIsNotFrustrated() {
        assertThat(service.isFrustrated("Hello, I need help with my order")).isFalse();
    }

    // ── Signal list ──────────────────────────────────────────────────────────

    @Test
    void getFrustrationSignalsReturnsMatchedCategories() {
        assertThat(service.getFrustrationSignals("CHEATED ME AGAIN VERY BADLY!!!"))
                .containsExactlyInAnyOrder("keyword", "punctuation", "all-caps");
    }

    @Test
    void getFrustrationSignalsReturnsEmptyForNormalMessage() {
        assertThat(service.getFrustrationSignals("What is the price?")).isEmpty();
    }
}
