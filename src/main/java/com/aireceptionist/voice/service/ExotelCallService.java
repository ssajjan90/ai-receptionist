package com.aireceptionist.voice.service;

import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.LanguageDetectionService;
import com.aireceptionist.ai.service.LlmService;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.event.VoiceCallbackRequestedEvent;
import com.aireceptionist.common.event.VoiceHumanFallbackRequestedEvent;
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.voice.VoiceTransferPort;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.domain.VoiceCallStatus;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 6.1 (AC4, AC5): records the received call. {@code voice_calls} has
 * {@code FORCE ROW LEVEL SECURITY} (V8__create_rls_policies.sql) — {@link TenantContext} must be
 * set before any repository call.
 *
 * <p>Story 6.2 (AC2-AC6): {@link #handleTranscript} runs one caller turn synchronously — Exotel
 * needs the AI answer in the same HTTP response — through the same {@link LlmService} (and so the
 * same {@code KnowledgeBaseService} KB + cache, story 6.4) as WhatsApp. The confidence gate is the
 * {@code AiConfidenceGateAspect} on {@code LlmService.generateResponse}: below 0.50 it swaps in the
 * fallback text and flags the query for owner training.
 *
 * <p>Story 6.3: low confidence or an explicit request for a person puts the call on hold for a
 * human ({@code voice:transfer:{callSid}} in Redis, 120s, plus a per-tenant pointer to the waiting
 * call) and alerts the owner on WhatsApp via a {@code common.event} event. The owner transfers by
 * replying TRANSFER ({@link #transferWaitingCall}) or via the REST endpoint ({@link #transferCall}).
 * Transfer, hold timeout and hang-up race for the Redis key with an atomic GETDEL. When the key is
 * gone for any reason other than a completed transfer (expiry, Redis error, a transfer that failed
 * mid-flight), the timeout falls back to the call's DB status so the caller is never left in
 * silence without a follow-up (code review).
 *
 * <p>Deliberately not {@code @Transactional}: the LLM call can take seconds and must not pin a DB
 * connection. Each repository save commits on its own; events are published inside a short
 * {@link TransactionTemplate} because {@code @ApplicationModuleListener}s only fire after a commit.
 */
@Service
public class ExotelCallService implements VoiceTransferPort {

    private static final Logger log = LoggerFactory.getLogger(ExotelCallService.class);
    static final double HUMAN_FALLBACK_CONFIDENCE = 0.50;
    public static final String TRANSFER_KEY_PREFIX = "voice:transfer:";
    public static final String WAITING_CALL_KEY_PREFIX = "voice:transfer:waiting:";
    static final Duration TRANSFER_TTL = Duration.ofSeconds(120);
    public static final String HOLD_MESSAGE = "Please hold while I connect you to our team member";
    public static final String NO_AGENT_MESSAGE =
            "No agent is available right now. I'll have someone WhatsApp you shortly.";
    static final String EMPTY_ANSWER_FALLBACK = "Sorry, I don't have an answer for that right now.";

    /** Story 6.3 Task 1. Matched case-insensitively on word boundaries ("human" ≠ "humane"). */
    public static final Set<String> HUMAN_REQUEST_SIGNALS =
            Set.of("speak to someone", "connect me to a person", "real person", "manager", "human");
    private static final List<Pattern> SIGNAL_PATTERNS = HUMAN_REQUEST_SIGNALS.stream()
            .map(signal -> Pattern.compile("\\b" + Pattern.quote(signal) + "\\b", Pattern.CASE_INSENSITIVE))
            .toList();

    private final VoiceCallRepository voiceCallRepository;
    private final LlmService llmService;
    private final LanguageDetectionService languageDetectionService;
    private final ConversationContextService conversationContextService;
    private final TenantNamePort tenantNamePort;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ExotelApiClient exotelApiClient;
    private final Clock clock;

    public ExotelCallService(VoiceCallRepository voiceCallRepository,
                             LlmService llmService,
                             LanguageDetectionService languageDetectionService,
                             ConversationContextService conversationContextService,
                             TenantNamePort tenantNamePort,
                             TenantOwnerPhonePort tenantOwnerPhonePort,
                             StringRedisTemplate redisTemplate,
                             ApplicationEventPublisher eventPublisher,
                             PlatformTransactionManager transactionManager,
                             ExotelApiClient exotelApiClient,
                             Clock clock) {
        this.voiceCallRepository = voiceCallRepository;
        this.llmService = llmService;
        this.languageDetectionService = languageDetectionService;
        this.conversationContextService = conversationContextService;
        this.tenantNamePort = tenantNamePort;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.exotelApiClient = exotelApiClient;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void onVoiceCallReceived(VoiceCallReceivedEvent event) {
        String tenantId = event.getTenantId();
        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);
        try {
            // Story 6.2: the transcript webhook may already have created the row (find-or-create)
            // if this async listener ran late — stay idempotent on call_sid.
            if (voiceCallRepository.findByCallSid(event.getCallSid()).isPresent()) {
                return;
            }
            VoiceCall call = VoiceCall.receive(UUID.fromString(tenantId), event.getCallSid(), event.getCallerPhone());
            voiceCallRepository.save(call);
            log.info("Voice call received: tenant={} callSid={}", tenantId, event.getCallSid());
        } catch (DataIntegrityViolationException ex) {
            // Only a concurrent insert of the same call is benign; anything else (bad data) is a real error.
            if (voiceCallRepository.findByCallSid(event.getCallSid()).isPresent()) {
                log.debug("Voice call {} already recorded concurrently", event.getCallSid());
            } else {
                log.error("Failed to record voice call {} for tenant={}: {}", event.getCallSid(), tenantId,
                        ex.getMostSpecificCause().getMessage());
            }
        } finally {
            MDC.remove("tenantId");
            TenantContext.clear();
        }
    }

    /**
     * Story 6.2 (AC2-AC6) / 6.3 (AC1): processes one transcribed caller utterance. Caller must have
     * set {@link TenantContext} to {@code tenantId}; {@code callerPhone} is already normalised
     * ({@link CallerPhone}). {@code businessPhone} is the Exotel number dialled (caller ID on transfer).
     */
    public VoiceTurnResult handleTranscript(UUID tenantId, String callSid, String callerPhone,
                                            String businessPhone, String transcript) {
        String tenant = tenantId.toString();
        Language language = languageDetectionService.detectLanguage(transcript);
        // Withheld/unusable caller IDs must not share one conversation history (code review).
        String contextPhone = CallerPhone.isUsable(callerPhone) ? callerPhone : null;

        if (isHumanRequest(transcript)) {
            boolean held = requestHumanFallback(tenantId, callSid, callerPhone, businessPhone,
                    "Caller asked for a person");
            String reply = held ? HOLD_MESSAGE : NO_AGENT_MESSAGE;
            VoiceCall call = findOrCreate(tenantId, callSid, callerPhone);
            call.recordHumanRequest(transcript, reply, Instant.now(clock));
            if (!held) {
                call.markMissed(Instant.now(clock));
            }
            voiceCallRepository.save(call);
            if (!held) {
                publishCallback(tenant, callSid, callerPhone);
            }
            return new VoiceTurnResult(reply, 1.0, language.toLangCode(),
                    held ? VoiceTurnResult.Outcome.HOLD_FOR_TRANSFER : VoiceTurnResult.Outcome.NO_AGENT);
        }

        String businessName = tenantNamePort.getBusinessName(tenant).orElse("Business");
        if (contextPhone != null) {
            conversationContextService.addTurn(tenant, contextPhone, "customer", transcript);
        }
        AiResponseResult result = llmService.generateResponse(tenant, businessName, transcript, contextPhone, language);
        // Code review: guard against a blank answer or a non-finite confidence from the LLM.
        String answer = result.response() == null || result.response().isBlank() ? EMPTY_ANSWER_FALLBACK : result.response();
        double confidence = Double.isFinite(result.confidence()) && !answer.equals(EMPTY_ANSWER_FALLBACK)
                ? result.confidence() : 0.0;
        if (contextPhone != null) {
            conversationContextService.addTurn(tenant, contextPhone, "ai", answer);
        }

        VoiceCall call = findOrCreate(tenantId, callSid, callerPhone);
        call.recordTurn(transcript, answer, confidence, Instant.now(clock));
        voiceCallRepository.save(call);

        VoiceTurnResult.Outcome outcome = VoiceTurnResult.Outcome.ANSWER;
        if (confidence < HUMAN_FALLBACK_CONFIDENCE) {
            // With no owner to alert, keep the conversation going on the fallback answer.
            boolean held = requestHumanFallback(tenantId, callSid, callerPhone, businessPhone,
                    String.format(Locale.ROOT, "AI unsure (confidence %.2f)", confidence));
            outcome = held ? VoiceTurnResult.Outcome.HOLD_FOR_TRANSFER : VoiceTurnResult.Outcome.ANSWER;
        }
        log.info("Voice turn handled: tenant={} callSid={} confidence={} outcome={}", tenant, callSid, confidence, outcome);
        return new VoiceTurnResult(answer, confidence, language.toLangCode(), outcome);
    }

    public static boolean isHumanRequest(String transcript) {
        return transcript != null && SIGNAL_PATTERNS.stream().anyMatch(p -> p.matcher(transcript).find());
    }

    /**
     * Story 6.3 (AC1, AC2): park the call for transfer and alert the owner. Returns false (no hold)
     * when there's no owner phone to alert. A Redis failure here doesn't stop the hold or the alert:
     * the timeout's DB fallback still guarantees the caller a follow-up (code review).
     */
    boolean requestHumanFallback(UUID tenantId, String callSid, String callerPhone, String businessPhone, String reason) {
        String tenant = tenantId.toString();
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenant).filter(p -> !p.isBlank()).orElse(null);
        if (ownerPhone == null) {
            log.warn("Human fallback for tenant={} callSid={} but no owner phone on record — not holding", tenant, callSid);
            return false;
        }
        try {
            redisTemplate.opsForValue().set(TRANSFER_KEY_PREFIX + callSid,
                    PendingTransfer.encode(callerPhone, businessPhone), TRANSFER_TTL);
            redisTemplate.opsForValue().set(WAITING_CALL_KEY_PREFIX + tenant, callSid, TRANSFER_TTL);
        } catch (Exception ex) {
            log.error("Could not park call {} for transfer (Redis): {}", callSid, ex.getMessage());
        }
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceHumanFallbackRequestedEvent(tenant, callSid, callerPhone, ownerPhone, reason)));
        log.info("Human fallback requested: tenant={} callSid={} reason={}", tenant, callSid, reason);
        return true;
    }

    /**
     * Story 6.3 (AC3): bridge a held call to the owner. Caller (the owner's request) must have
     * {@link TenantContext} set — RLS makes another tenant's call SID simply not found.
     */
    public void transferCall(String callSid, String ownerPhone) {
        VoiceCall call = voiceCallRepository.findByCallSid(callSid)
                .orElseThrow(() -> new NotFoundException("VOICE_CALL_NOT_FOUND", "Voice call not found."));
        if (ownerPhone == null || ownerPhone.isBlank()) {
            throw new BusinessRuleException("OWNER_PHONE_MISSING", "No owner phone is registered for transfers.");
        }
        String key = TRANSFER_KEY_PREFIX + callSid;
        String pending = redisTemplate.opsForValue().getAndDelete(key);
        if (pending == null) {
            throw new BusinessRuleException("TRANSFER_NOT_PENDING",
                    "This call is no longer waiting for a transfer.");
        }
        try {
            exotelApiClient.connectCall(callSid, ownerPhone, PendingTransfer.decode(pending).businessPhone());
        } catch (RuntimeException ex) {
            // Give the owner another chance while the caller is still on hold.
            redisTemplate.opsForValue().set(key, pending, Duration.ofSeconds(60));
            throw ex;
        }
        call.markTransferred(Instant.now(clock));
        voiceCallRepository.save(call);
        clearWaitingPointer(call.getTenantId().toString(), callSid);
        log.info("Voice call transferred: callSid={}", callSid);
    }

    /** Story 6.3 (review decision): owner replied TRANSFER on WhatsApp. */
    @Override
    public VoiceTransferPort.Outcome transferWaitingCall(String tenantId, String ownerPhone) {
        String callSid;
        try {
            callSid = redisTemplate.opsForValue().get(WAITING_CALL_KEY_PREFIX + tenantId);
        } catch (Exception ex) {
            log.error("Could not look up waiting call for tenant={}: {}", tenantId, ex.getMessage());
            return VoiceTransferPort.Outcome.FAILED;
        }
        if (callSid == null) {
            return VoiceTransferPort.Outcome.NO_CALL_WAITING;
        }
        try {
            transferCall(callSid, ownerPhone);
            return VoiceTransferPort.Outcome.TRANSFERRED;
        } catch (NotFoundException | BusinessRuleException ex) {
            return VoiceTransferPort.Outcome.NO_CALL_WAITING;
        } catch (ExternalServiceException ex) {
            return VoiceTransferPort.Outcome.FAILED;
        } catch (RuntimeException ex) {
            log.error("Transfer of call {} failed: {}", callSid, ex.getMessage());
            return VoiceTransferPort.Outcome.FAILED;
        }
    }

    /**
     * Story 6.3 (AC5): the hold window ended. Returns false only when the call was actually
     * transferred; otherwise the call is marked MISSED and a WhatsApp follow-up requested.
     */
    public boolean handleTransferTimeout(UUID tenantId, String callSid, String callerPhone) {
        String tenant = tenantId.toString();
        String pending = takePending(callSid);
        Optional<VoiceCall> existing = voiceCallRepository.findByCallSid(callSid);
        if (pending == null && existing.map(c -> c.getStatus() == VoiceCallStatus.TRANSFERRED).orElse(false)) {
            return false;
        }
        String followUpPhone = pending != null
                ? Optional.ofNullable(PendingTransfer.decode(pending).callerPhone()).filter(p -> !p.isBlank()).orElse(callerPhone)
                : callerPhone;
        VoiceCall call = existing.orElseGet(() -> findOrCreate(tenantId, callSid, followUpPhone));
        call.markMissed(Instant.now(clock));
        voiceCallRepository.save(call);
        clearWaitingPointer(tenant, callSid);
        publishCallback(tenant, callSid, followUpPhone);
        log.info("Transfer timed out, WhatsApp follow-up requested: tenant={} callSid={}", tenant, callSid);
        return true;
    }

    /**
     * Story 6.2 (AC5), review decision: Exotel's status callback — the call is over. Records the
     * real end time/duration. If the caller hung up while on hold for a human, the call is MISSED
     * and followed up, exactly like a timeout.
     */
    public void handleCallEnded(UUID tenantId, String callSid, String callerPhone) {
        String tenant = tenantId.toString();
        String pending = takePending(callSid);
        Optional<VoiceCall> existing = voiceCallRepository.findByCallSid(callSid);
        if (pending != null) {
            String followUpPhone = Optional.ofNullable(PendingTransfer.decode(pending).callerPhone())
                    .filter(p -> !p.isBlank()).orElse(callerPhone);
            VoiceCall call = existing.orElseGet(() -> findOrCreate(tenantId, callSid, followUpPhone));
            call.markMissed(Instant.now(clock));
            voiceCallRepository.save(call);
            clearWaitingPointer(tenant, callSid);
            publishCallback(tenant, callSid, followUpPhone);
            log.info("Caller hung up while on hold: tenant={} callSid={}", tenant, callSid);
            return;
        }
        existing.ifPresent(call -> {
            call.markEnded(Instant.now(clock));
            voiceCallRepository.save(call);
        });
    }

    private String takePending(String callSid) {
        try {
            return redisTemplate.opsForValue().getAndDelete(TRANSFER_KEY_PREFIX + callSid);
        } catch (Exception ex) {
            log.error("Could not read transfer state for call {} (Redis): {}", callSid, ex.getMessage());
            return null;
        }
    }

    private void clearWaitingPointer(String tenantId, String callSid) {
        try {
            String key = WAITING_CALL_KEY_PREFIX + tenantId;
            if (callSid.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
            }
        } catch (Exception ex) {
            log.debug("Could not clear waiting-call pointer for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    private void publishCallback(String tenant, String callSid, String callerPhone) {
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenant).filter(p -> !p.isBlank()).orElse(null);
        String followUp = CallerPhone.isUsable(callerPhone) ? callerPhone : null;
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceCallbackRequestedEvent(tenant, callSid, followUp, ownerPhone)));
    }

    VoiceCall findOrCreate(UUID tenantId, String callSid, String callerPhone) {
        return voiceCallRepository.findByCallSid(callSid).orElseGet(() -> {
            try {
                return voiceCallRepository.saveAndFlush(VoiceCall.receive(tenantId, callSid, callerPhone));
            } catch (DataIntegrityViolationException ex) {
                // The 6.1 async listener inserted it between our read and write.
                return voiceCallRepository.findByCallSid(callSid).orElseThrow(() -> ex);
            }
        });
    }

    /** Redis value for {@code voice:transfer:{callSid}}: {@code callerPhone|businessPhone}. */
    record PendingTransfer(String callerPhone, String businessPhone) {
        static String encode(String callerPhone, String businessPhone) {
            return (callerPhone == null ? "" : callerPhone) + "|" + (businessPhone == null ? "" : businessPhone);
        }

        static PendingTransfer decode(String value) {
            String[] parts = value.split("\\|", 2);
            String business = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
            return new PendingTransfer(parts[0], business);
        }
    }
}
