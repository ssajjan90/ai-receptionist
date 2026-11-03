package com.aireceptionist.voice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 6.1 (AC2, AC4). Fields follow the actual {@code voice_calls} schema
 * (V6__create_voice_calls_table.sql) rather than the story's illustrative Task 1 field list,
 * which named columns (duration, transcript, aiResponseCount, createdAt) and status values
 * (ACTIVE/COMPLETED/FAILED) that don't exist in that migration — same kind of Dev-Notes-vs-
 * established-artifact drift as story 5.6's BYPASSRLS note. duration/transcript/aiResponseCount
 * are presumably introduced by a later voice story (6.2+); this story only receives and records
 * the call.
 *
 * <p>Story 6.2 (AC5) adds {@code duration_seconds}, {@code transcript}, {@code ai_response_count}
 * and {@code last_confidence} (V27). The story's {@code createdAt} maps to the existing
 * {@code started_at}. Story 6.4 (AC3): text only — there is deliberately no audio column.
 */
@Entity
@Table(name = "voice_calls")
public class VoiceCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "call_sid")
    private String callSid;

    @Column(name = "caller_phone", nullable = false)
    private String callerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VoiceCallStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "transcript", columnDefinition = "TEXT")
    private String transcript;

    @Column(name = "ai_response_count", nullable = false)
    private int aiResponseCount;

    @Column(name = "last_confidence", precision = 3, scale = 2)
    private BigDecimal lastConfidence;

    protected VoiceCall() {
    }

    private VoiceCall(UUID tenantId, String callSid, String callerPhone) {
        this.tenantId = tenantId;
        this.callSid = callSid;
        this.callerPhone = callerPhone;
        this.status = VoiceCallStatus.RECEIVED;
        this.startedAt = Instant.now();
    }

    public static VoiceCall receive(UUID tenantId, String callSid, String callerPhone) {
        return new VoiceCall(tenantId, callSid, callerPhone);
    }

    /**
     * Story 6.2 (AC5): appends one caller/AI exchange to the transcript and bumps the AI response
     * count. Duration is a rolling "connected so far" figure, refreshed every turn and finalised
     * by {@link #end}.
     */
    public void recordTurn(String callerText, String aiText, double confidence, Instant now) {
        // Code review: NaN/infinite confidence would make BigDecimal.valueOf throw mid-call.
        double safe = Double.isFinite(confidence) ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0;
        BigDecimal rounded = BigDecimal.valueOf(safe).setScale(2, RoundingMode.HALF_UP);
        // Per-turn confidence is kept in the transcript line (6.4 AC3); last_confidence is the latest.
        String turn = "Caller: " + callerText + "\nAI (" + rounded.toPlainString() + "): " + aiText + "\n";
        this.transcript = transcript == null ? turn : transcript + turn;
        this.aiResponseCount++;
        this.lastConfidence = rounded;
        this.status = VoiceCallStatus.HANDLED;
        this.durationSeconds = (int) Duration.between(startedAt, now).toSeconds();
    }

    /**
     * Story 6.3 (AC1): caller explicitly asked for a human — no AI answer was generated, so the
     * AI response count and confidence are left untouched.
     */
    public void recordHumanRequest(String callerText, String systemText, Instant now) {
        String turn = "Caller: " + callerText + "\nSystem: " + systemText + "\n";
        this.transcript = transcript == null ? turn : transcript + turn;
        this.durationSeconds = (int) Duration.between(startedAt, now).toSeconds();
    }

    /**
     * Story 6.2 (AC5), review decision: Exotel's status callback says the call is over. Records
     * the real end time and duration; RECEIVED/HANDLED/TRANSFERRED/MISSED status is kept.
     */
    public void markEnded(Instant now) {
        end(now);
    }

    /** Story 6.3 (AC3). */
    public void markTransferred(Instant now) {
        this.status = VoiceCallStatus.TRANSFERRED;
        end(now);
    }

    /** Story 6.3 (AC5): human fallback requested but no one picked up. */
    public void markMissed(Instant now) {
        this.status = VoiceCallStatus.MISSED;
        end(now);
    }

    private void end(Instant now) {
        this.endedAt = now;
        this.durationSeconds = (int) Duration.between(startedAt, now).toSeconds();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCallSid() {
        return callSid;
    }

    public String getCallerPhone() {
        return callerPhone;
    }

    public VoiceCallStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public String getTranscript() {
        return transcript;
    }

    public int getAiResponseCount() {
        return aiResponseCount;
    }

    public BigDecimal getLastConfidence() {
        return lastConfidence;
    }
}
