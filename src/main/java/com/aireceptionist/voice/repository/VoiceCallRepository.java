package com.aireceptionist.voice.repository;

import com.aireceptionist.voice.domain.VoiceCall;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VoiceCallRepository extends JpaRepository<VoiceCall, UUID> {

    Optional<VoiceCall> findByCallSid(String callSid);

    /**
     * Story 6.1 Task 1 named this {@code findByTenantIdOrderByCreatedAtDesc}, but {@link VoiceCall}
     * has no {@code createdAt} field (the actual schema uses {@code started_at} — see VoiceCall's
     * javadoc) — ordering by the field that actually exists.
     */
    Page<VoiceCall> findByTenantIdOrderByStartedAtDesc(UUID tenantId, Pageable pageable);
}
