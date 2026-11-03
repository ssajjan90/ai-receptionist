-- Story 6.2 (AC5) / 6.4 (AC3): conversation metadata for voice calls.
-- Text only — raw audio is never stored here (synthesized TTS audio lives briefly in Redis).
ALTER TABLE voice_calls
    ADD COLUMN duration_seconds  INTEGER,
    ADD COLUMN transcript        TEXT,
    ADD COLUMN ai_response_count INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN last_confidence   NUMERIC(3, 2);
