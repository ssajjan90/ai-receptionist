package com.aireceptionist.voice.domain;

/** Story 6.1. Matches the {@code chk_voice_calls_status} CHECK constraint in V6__create_voice_calls_table.sql. */
public enum VoiceCallStatus {
    RECEIVED,
    HANDLED,
    TRANSFERRED,
    MISSED
}
