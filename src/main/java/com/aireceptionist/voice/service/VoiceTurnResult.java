package com.aireceptionist.voice.service;

/**
 * Story 6.2: outcome of one caller utterance. {@code humanFallbackRequired} is the confidence gate
 * (AC6) — story 6.3 turns it into the hold-and-transfer flow.
 */
public record VoiceTurnResult(String responseText, double confidence, String languageCode,
                              boolean humanFallbackRequired) {
}
