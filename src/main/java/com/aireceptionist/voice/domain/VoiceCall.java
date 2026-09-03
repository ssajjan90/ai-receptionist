package com.aireceptionist.voice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
}
