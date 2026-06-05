package com.aireceptionist.common.ai;

public record AiResponseResult(String response, double confidence, boolean flaggedForReview) {

    public AiResponseResult(String response, double confidence) {
        this(response, confidence, false);
    }

    public AiResponseResult flagged() {
        return new AiResponseResult(response, confidence, true);
    }
}
