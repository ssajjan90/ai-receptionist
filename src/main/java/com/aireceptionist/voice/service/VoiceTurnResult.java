package com.aireceptionist.voice.service;

/**
 * Story 6.2/6.3: outcome of one caller utterance.
 * <ul>
 *   <li>{@code ANSWER} — speak {@code responseText}, keep listening.</li>
 *   <li>{@code HOLD_FOR_TRANSFER} — needs a human and the owner was alerted: hold (AC4).</li>
 *   <li>{@code NO_AGENT} — caller asked for a person but there's no owner phone to alert:
 *       skip the pointless 60s hold, apologise and follow up (code review).</li>
 * </ul>
 */
public record VoiceTurnResult(String responseText, double confidence, String languageCode, Outcome outcome) {

    public enum Outcome { ANSWER, HOLD_FOR_TRANSFER, NO_AGENT }

    public boolean humanFallbackRequired() {
        return outcome != Outcome.ANSWER;
    }
}
