package com.aireceptionist.voice.service;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 6.1 (AC4, AC5) stub: records the received call. The AI voice pipeline itself is wired in
 * Story 6.2. {@code voice_calls} has {@code FORCE ROW LEVEL SECURITY} (V8__create_rls_policies.sql)
 * — {@link TenantContext} must be set before the save, mirroring
 * {@code WhatsAppMessageService.onInboundMessage}'s established pattern for the same reason.
 */
@Service
public class ExotelCallService {

    private static final Logger log = LoggerFactory.getLogger(ExotelCallService.class);

    private final VoiceCallRepository voiceCallRepository;

    public ExotelCallService(VoiceCallRepository voiceCallRepository) {
        this.voiceCallRepository = voiceCallRepository;
    }

    @ApplicationModuleListener
    void onVoiceCallReceived(VoiceCallReceivedEvent event) {
        String tenantId = event.getTenantId();
        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);
        try {
            VoiceCall call = VoiceCall.receive(UUID.fromString(tenantId), event.getCallSid(), event.getCallerPhone());
            voiceCallRepository.save(call);
            log.info("Voice call received: tenant={} callSid={}", tenantId, event.getCallSid());
        } finally {
            MDC.remove("tenantId");
            TenantContext.clear();
        }
    }
}
