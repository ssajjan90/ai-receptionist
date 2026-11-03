package com.aireceptionist.voice.api;

import com.aireceptionist.tenant.port.in.ResolveTenantByBusinessPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantVoiceRoute;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.service.ExoMl;
import com.aireceptionist.voice.service.ExotelCallService;
import com.aireceptionist.voice.service.VoiceAudioStore;
import com.aireceptionist.voice.service.VoiceResponseGenerator;
import com.aireceptionist.voice.service.VoiceTurnResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Story 6.1 (AC1-AC5). Exotel sends {@code CallSid}/{@code From}/{@code To}/{@code Direction}/
 * {@code Status} as form params (not JSON) — mirrors {@code TwilioWebhookController}'s
 * {@code @RequestParam} style rather than {@code WhatsAppWebhookController}'s raw-JSON-body style.
 * NOT extending {@code VersionedRestController} (Dev Notes) — Exotel calls a fixed, unversioned
 * ExoML URL, same reasoning as the other webhook controllers in this codebase. Signature checks
 * live in {@link ExotelSignatureVerifier} (see its javadoc for the canonicalisation assumption).
 *
 * <p>Story 6.2: the PRO/ENTERPRISE answer now plays the tenant greeting before recording the
 * caller's first utterance (AC1, AC2), and {@code /webhooks/voice/transcript} runs each
 * transcribed utterance through the AI pipeline and speaks the answer back (AC3, AC4).
 * {@code /webhooks/voice/audio/{id}} serves Google-TTS audio for {@code <Play>}.
 *
 * <p>Story 6.3: a turn needing a human returns hold ExoML ({@code <Say>} + 60s {@code <Pause>} +
 * {@code <Redirect>} to {@code /webhooks/voice/transfer-timeout}). The story's Dev Notes describe
 * Exotel calling an "action URL" after the pause; {@code <Pause>} has no action attribute, so a
 * {@code <Redirect>} follows it — confirm against Exotel's ExoML docs with a live account.
 *
 * <p>AC5 (voice/WhatsApp failure independence): satisfied by construction — this controller shares
 * no service, repository, or queue with the whatsapp module.
 */
@RestController
public class VoiceWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Exotel-Signature";

    // Story 6.2: was <Play>text</Play> — <Play> takes an audio URL, so Exotel couldn't speak it.
    private static final String UNAVAILABLE_RESPONSE = ExoMl.response(
            ExoMl.say("We're unable to process your call right now. Please try again later.", "en"),
            ExoMl.hangup());

    private static final String BASIC_TIER_REDIRECT_RESPONSE = ExoMl.response(
            ExoMl.say("Please visit our WhatsApp at +91XXXXXXXXXX for assistance.", "en"),
            ExoMl.hangup());

    static final String NOT_HEARD_PROMPT = "Sorry, I didn't catch that. Could you please repeat your question?";
    static final String PROCESSING_ERROR_PROMPT =
            "Sorry, I'm having trouble right now. Could you please ask that again?";

    static final int HOLD_SECONDS = 60;
    static final String NO_AGENT_MESSAGE =
            "No agent is available right now. I'll have someone WhatsApp you shortly.";

    private final ExotelSignatureVerifier signatureVerifier;
    private final ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase;
    private final ApplicationEventPublisher eventPublisher;
    private final VoiceResponseGenerator responseGenerator;
    private final ExotelCallService exotelCallService;
    private final VoiceAudioStore audioStore;
    private final TransactionTemplate transactionTemplate;

    public VoiceWebhookController(ExotelSignatureVerifier signatureVerifier,
                                  ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase,
                                  ApplicationEventPublisher eventPublisher,
                                  VoiceResponseGenerator responseGenerator,
                                  ExotelCallService exotelCallService,
                                  VoiceAudioStore audioStore,
                                  PlatformTransactionManager transactionManager) {
        this.signatureVerifier = signatureVerifier;
        this.resolveTenantByBusinessPhoneUseCase = resolveTenantByBusinessPhoneUseCase;
        this.eventPublisher = eventPublisher;
        this.responseGenerator = responseGenerator;
        this.exotelCallService = exotelCallService;
        this.audioStore = audioStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostMapping(value = "/webhooks/voice", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveCall(
            @RequestParam("CallSid") String callSid,
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestParam(value = "Direction", required = false, defaultValue = "") String direction,
            @RequestParam(value = "Status", required = false, defaultValue = "") String status,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to, direction, status)) {
            log.warn("Exotel webhook signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }

        Optional<ResolvedTenantVoiceRoute> route = resolveRoute(request, to);
        if (route.isEmpty()) {
            log.warn("No tenant found for Exotel To={}", to);
            return exoml(UNAVAILABLE_RESPONSE);
        }

        ResolvedTenantVoiceRoute resolved = route.get();
        if ("BASIC".equals(resolved.tier())) {
            return exoml(BASIC_TIER_REDIRECT_RESPONSE);
        }

        // @ApplicationModuleListener is a @TransactionalEventListener (fallbackExecution=false):
        // published with no transaction in scope, ExotelCallService.onVoiceCallReceived silently
        // never runs (story 6.1's "environmental" async failure). Publishing inside a committed
        // transaction is what makes the listener fire — and records the event publication.
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceCallReceivedEvent(resolved.tenantId().toString(), callSid, from, to)));
        // Story 6.2 (AC1, AC2): greeting is pre-generated/cached, so this adds no TTS latency;
        // Exotel records the first utterance right after it plays.
        return exoml(ExoMl.response(
                responseGenerator.getGreetingAudio(resolved.tenantId()),
                ExoMl.recordForTranscript()));
    }

    /**
     * Story 6.2 (AC2-AC6): Exotel posts the transcription of the caller's recorded utterance here.
     * Signature covers CallSid+From+To+TranscriptionText so a captured request can't be replayed
     * with a different transcript.
     */
    @PostMapping(value = "/webhooks/voice/transcript", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveTranscript(
            @RequestParam("CallSid") String callSid,
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestParam(value = "TranscriptionText", required = false, defaultValue = "") String transcriptionText,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to, transcriptionText)) {
            log.warn("Exotel transcript signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }

        Optional<ResolvedTenantVoiceRoute> route = resolveRoute(request, to);
        if (route.isEmpty()) {
            log.warn("No tenant found for Exotel transcript To={}", to);
            return exoml(UNAVAILABLE_RESPONSE);
        }
        ResolvedTenantVoiceRoute resolved = route.get();
        if ("BASIC".equals(resolved.tier())) {
            return exoml(BASIC_TIER_REDIRECT_RESPONSE);
        }

        String transcript = transcriptionText.trim();
        if (transcript.isEmpty()) {
            return exoml(ExoMl.response(ExoMl.say(NOT_HEARD_PROMPT, "en"), ExoMl.recordForTranscript()));
        }

        // TenantContext was set by VoiceTenantContextFilter (must precede open-in-view — see its javadoc).
        try {
            VoiceTurnResult result = exotelCallService.handleTranscript(resolved.tenantId(), callSid, from, to, transcript);
            if (result.humanFallbackRequired()) {
                // Story 6.3 (AC4): hold while the owner decides; after the pause Exotel moves on to
                // the transfer-timeout handler (unless the owner bridged the call first).
                return exoml(ExoMl.response(
                        ExoMl.say(ExotelCallService.HOLD_MESSAGE, result.languageCode()),
                        ExoMl.pause(HOLD_SECONDS),
                        ExoMl.redirect(ExoMl.TRANSFER_TIMEOUT_ACTION)));
            }
            return exoml(ExoMl.response(
                    responseGenerator.speak(result.responseText(), result.languageCode()),
                    ExoMl.recordForTranscript()));
        } catch (Exception ex) {
            log.error("Voice transcript processing failed for CallSid={}: {}", callSid, ex.getMessage(), ex);
            return exoml(ExoMl.response(ExoMl.say(PROCESSING_ERROR_PROMPT, "en"), ExoMl.recordForTranscript()));
        }
    }

    /**
     * Story 6.3 (AC5): reached after the 60s hold. If the owner never transferred, the caller is
     * told no one is available and gets a WhatsApp follow-up; if the call was already bridged
     * there is nothing left to say.
     */
    @PostMapping(value = "/webhooks/voice/transfer-timeout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> transferTimeout(
            @RequestParam("CallSid") String callSid,
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to)) {
            log.warn("Exotel transfer-timeout signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }
        Optional<ResolvedTenantVoiceRoute> route = resolveRoute(request, to);
        if (route.isEmpty()) {
            return exoml(UNAVAILABLE_RESPONSE);
        }
        try {
            if (exotelCallService.handleTransferTimeout(route.get().tenantId(), callSid, from)) {
                return exoml(ExoMl.response(ExoMl.say(NO_AGENT_MESSAGE, "en"), ExoMl.hangup()));
            }
        } catch (Exception ex) {
            log.error("Transfer-timeout handling failed for CallSid={}: {}", callSid, ex.getMessage(), ex);
            return exoml(ExoMl.response(ExoMl.say(NO_AGENT_MESSAGE, "en"), ExoMl.hangup()));
        }
        return exoml(ExoMl.response());
    }

    /** Story 6.2: serves Google-TTS audio referenced by {@code <Play>}. IDs are random UUIDs with a short TTL. */
    @GetMapping("/webhooks/voice/audio/{audioId}")
    public ResponseEntity<byte[]> audio(@PathVariable String audioId) {
        return audioStore.load(audioId)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<ResolvedTenantVoiceRoute> resolveRoute(HttpServletRequest request, String to) {
        Object fromFilter = request.getAttribute(VoiceTenantContextFilter.ROUTE_ATTRIBUTE);
        if (fromFilter instanceof ResolvedTenantVoiceRoute route) {
            return Optional.of(route);
        }
        return resolveTenantByBusinessPhoneUseCase.resolveByBusinessPhone(to);
    }

    private ResponseEntity<String> exoml(String xml) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml);
    }
}
