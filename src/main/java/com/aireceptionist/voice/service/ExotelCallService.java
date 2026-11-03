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
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
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
 * human ({@code voice:transfer:{callSid}} in Redis, 120s) and alerts the owner on WhatsApp via a
 * {@code common.event} event. An explicit request skips the LLM entirely — there's no question to
 * answer. {@link #transferCall} and {@link #handleTransferTimeout} race for that Redis key with an
 * atomic GETDEL, so exactly one of "transferred" / "missed" wins.
 *
 * <p>Deliberately not {@code @Transactional}: the LLM call can take seconds and must not pin a DB
 * connection. Each repository save commits on its own; events are published inside a short
 * {@link TransactionTemplate} because {@code @ApplicationModuleListener}s only fire after a commit.
 */
@Service
public class ExotelCallService {

    private static final Logger log = LoggerFactory.getLogger(ExotelCallService.class);
    static final double HUMAN_FALLBACK_CONFIDENCE = 0.50;
    public static final String TRANSFER_KEY_PREFIX = "voice:transfer:";
    static final Duration TRANSFER_TTL = Duration.ofSeconds(120);
    public static final String HOLD_MESSAGE = "Please hold while I connect you to our team member";

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
    private final String publicBaseUrl;
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
                             @Value("${app.voice.public-base-url:http://localhost:8080}") String publicBaseUrl,
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
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
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
            log.debug("Voice call {} already recorded concurrently", event.getCallSid());
        } finally {
            MDC.remove("tenantId");
            TenantContext.clear();
        }
    }

    /**
     * Story 6.2 (AC2-AC6) / 6.3 (AC1): processes one transcribed caller utterance. Caller must have
     * set {@link TenantContext} to {@code tenantId}. {@code businessPhone} is the Exotel number
     * dialled (used as caller ID if the call is later transferred).
     */
    public VoiceTurnResult handleTranscript(UUID tenantId, String callSid, String callerPhone,
                                            String businessPhone, String transcript) {
        String tenant = tenantId.toString();
        Language language = languageDetectionService.detectLanguage(transcript);

        if (isHumanRequest(transcript)) {
            VoiceCall call = findOrCreate(tenantId, callSid, callerPhone);
            call.recordHumanRequest(transcript, HOLD_MESSAGE, Instant.now(clock));
            voiceCallRepository.save(call);
            requestHumanFallback(tenantId, callSid, callerPhone, businessPhone, "Caller asked for a person");
            return new VoiceTurnResult(HOLD_MESSAGE, 1.0, language.toLangCode(), true);
        }

        String businessName = tenantNamePort.getBusinessName(tenant).orElse("Business");
        conversationContextService.addTurn(tenant, callerPhone, "customer", transcript);
        AiResponseResult result = llmService.generateResponse(tenant, businessName, transcript, callerPhone, language);
        conversationContextService.addTurn(tenant, callerPhone, "ai", result.response());

        VoiceCall call = findOrCreate(tenantId, callSid, callerPhone);
        call.recordTurn(transcript, result.response(), result.confidence(), Instant.now(clock));
        voiceCallRepository.save(call);

        boolean humanFallback = result.confidence() < HUMAN_FALLBACK_CONFIDENCE;
        if (humanFallback) {
            requestHumanFallback(tenantId, callSid, callerPhone, businessPhone,
                    String.format(Locale.ROOT, "AI unsure (confidence %.2f)", result.confidence()));
        }
        log.info("Voice turn handled: tenant={} callSid={} confidence={} humanFallback={}",
                tenant, callSid, result.confidence(), humanFallback);
        return new VoiceTurnResult(result.response(), result.confidence(), language.toLangCode(), humanFallback);
    }

    public static boolean isHumanRequest(String transcript) {
        return transcript != null && SIGNAL_PATTERNS.stream().anyMatch(p -> p.matcher(transcript).find());
    }

    /** Story 6.3 (AC1, AC2): park the call for transfer and alert the owner. */
    void requestHumanFallback(UUID tenantId, String callSid, String callerPhone, String businessPhone, String reason) {
        String tenant = tenantId.toString();
        redisTemplate.opsForValue().set(TRANSFER_KEY_PREFIX + callSid,
                PendingTransfer.encode(callerPhone, businessPhone), TRANSFER_TTL);
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenant).orElse(null);
        String transferUrl = publicBaseUrl + "/v1/voice/calls/" + callSid + "/transfer";
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceHumanFallbackRequestedEvent(tenant, callSid, callerPhone, ownerPhone, reason, transferUrl)));
        log.info("Human fallback requested: tenant={} callSid={} reason={}", tenant, callSid, reason);
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
        log.info("Voice call transferred: callSid={}", callSid);
    }

    /**
     * Story 6.3 (AC5): the hold window ended. Returns true if the call was still waiting (owner
     * never transferred) — it is marked MISSED and a WhatsApp follow-up is requested.
     */
    public boolean handleTransferTimeout(UUID tenantId, String callSid, String callerPhone) {
        String pending = redisTemplate.opsForValue().getAndDelete(TRANSFER_KEY_PREFIX + callSid);
        if (pending == null) {
            return false;
        }
        String tenant = tenantId.toString();
        String followUpPhone = Optional.ofNullable(PendingTransfer.decode(pending).callerPhone())
                .filter(p -> !p.isBlank()).orElse(callerPhone);
        VoiceCall call = findOrCreate(tenantId, callSid, followUpPhone);
        call.markMissed(Instant.now(clock));
        voiceCallRepository.save(call);
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenant).orElse(null);
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceCallbackRequestedEvent(tenant, callSid, followUpPhone, ownerPhone)));
        log.info("Transfer timed out, WhatsApp follow-up requested: tenant={} callSid={}", tenant, callSid);
        return true;
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
