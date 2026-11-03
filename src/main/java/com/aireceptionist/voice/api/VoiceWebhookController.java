package com.aireceptionist.voice.api;

import com.aireceptionist.tenant.port.in.ResolveTenantByBusinessPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantVoiceRoute;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import com.aireceptionist.voice.service.CallerPhone;
import com.aireceptionist.voice.service.ExoMl;
import com.aireceptionist.voice.service.ExotelCallService;
import com.aireceptionist.voice.service.VoiceAudioStore;
import com.aireceptionist.voice.service.VoiceCallbackUrls;
import com.aireceptionist.voice.service.VoiceResponseGenerator;
import com.aireceptionist.voice.service.VoiceTurnResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;

/**
 * Story 6.1 (AC1-AC5). Exotel sends {@code CallSid}/{@code From}/{@code To}/{@code Direction}/
 * {@code Status} as form params (not JSON) — mirrors {@code TwilioWebhookController}'s
 * {@code @RequestParam} style. NOT extending {@code VersionedRestController} (Dev Notes) — Exotel
 * calls fixed, unversioned URLs. Signature checks live in {@link ExotelSignatureVerifier} (see its
 * javadoc for the canonicalisation assumption).
 *
 * <p>Story 6.2: the PRO/ENTERPRISE answer plays the tenant greeting before recording the caller's
 * first utterance (AC1, AC2); {@code /webhooks/voice/transcript} runs each transcribed utterance
 * through the AI pipeline and speaks the answer back (AC3, AC4); {@code /webhooks/voice/status}
 * (Exotel StatusCallback) finalises the call (AC5); {@code /webhooks/voice/audio/{id}} serves
 * Google-TTS audio for {@code <Play>}.
 *
 * <p>Story 6.3: a turn needing a human returns hold ExoML ({@code <Say>} + 60s {@code <Pause>} +
 * {@code <Redirect>} to {@code /webhooks/voice/transfer-timeout}). {@code <Pause>} has no action
 * attribute, so a {@code <Redirect>} follows it — confirm against Exotel's ExoML docs (W121).
 *
 * <p>Every webhook answers with ExoML, never a JSON error: missing params, unknown numbers,
 * BASIC tier and non-LIVE tenants (SUSPENDED/PAYMENT_SUSPENDED/TERMINATED/ERASED — W97, mirroring
 * {@code WhatsAppMessageService}) are all handled before any AI work (code review).
 */
@RestController
public class VoiceWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Exotel-Signature";

    static final Set<String> BLOCKED_STATUSES = Set.of("SUSPENDED", "PAYMENT_SUSPENDED", "TERMINATED", "ERASED");

    private static final String UNAVAILABLE_RESPONSE = ExoMl.response(
            ExoMl.say("We're unable to process your call right now. Please try again later.", "en"),
            ExoMl.hangup());

    // Code review: was a literal "+91XXXXXXXXXX" placeholder spoken to callers. The tenant's
    // WhatsApp display number isn't persisted, so no number is read out.
    static final String BASIC_TIER_MESSAGE =
            "Thanks for calling. Voice support isn't available for this business. "
                    + "Please message us on WhatsApp for assistance.";
    private static final String BASIC_TIER_REDIRECT_RESPONSE = ExoMl.response(
            ExoMl.say(BASIC_TIER_MESSAGE, "en"), ExoMl.hangup());

    static final String NOT_HEARD_PROMPT = "Sorry, I didn't catch that. Could you please repeat your question?";
    static final String PROCESSING_ERROR_PROMPT =
            "Sorry, I'm having trouble right now. Could you please ask that again?";
    static final String TIMEOUT_ERROR_MESSAGE =
            "No agent is available right now. Please try calling again later.";
    static final int HOLD_SECONDS = 60;

    private final ExotelSignatureVerifier signatureVerifier;
    private final ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase;
    private final ApplicationEventPublisher eventPublisher;
    private final VoiceResponseGenerator responseGenerator;
    private final ExotelCallService exotelCallService;
    private final VoiceAudioStore audioStore;
    private final VoiceCallbackUrls urls;
    private final TransactionTemplate transactionTemplate;

    public VoiceWebhookController(ExotelSignatureVerifier signatureVerifier,
                                  ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase,
                                  ApplicationEventPublisher eventPublisher,
                                  VoiceResponseGenerator responseGenerator,
                                  ExotelCallService exotelCallService,
                                  VoiceAudioStore audioStore,
                                  VoiceCallbackUrls urls,
                                  PlatformTransactionManager transactionManager) {
        this.signatureVerifier = signatureVerifier;
        this.resolveTenantByBusinessPhoneUseCase = resolveTenantByBusinessPhoneUseCase;
        this.eventPublisher = eventPublisher;
        this.responseGenerator = responseGenerator;
        this.exotelCallService = exotelCallService;
        this.audioStore = audioStore;
        this.urls = urls;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostMapping(value = "/webhooks/voice", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveCall(
            @RequestParam(value = "CallSid", required = false, defaultValue = "") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "") String from,
            @RequestParam(value = "To", required = false, defaultValue = "") String to,
            @RequestParam(value = "Direction", required = false, defaultValue = "") String direction,
            @RequestParam(value = "Status", required = false, defaultValue = "") String status,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to, direction, status)) {
            log.warn("Exotel webhook signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }
        Gate gate = gate(request, callSid, to);
        if (gate.response != null) {
            return gate.response;
        }
        ResolvedTenantVoiceRoute resolved = gate.route;
        String caller = CallerPhone.normalize(from);

        // @ApplicationModuleListener is a @TransactionalEventListener (fallbackExecution=false):
        // published with no transaction in scope, ExotelCallService.onVoiceCallReceived silently
        // never runs. Publishing inside a committed transaction is what makes the listener fire.
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new VoiceCallReceivedEvent(resolved.tenantId().toString(), callSid, caller, to)));
        // Story 6.2 (AC1, AC2): greeting comes from cache (or <Say>) — never waits on TTS.
        return exoml(ExoMl.response(
                responseGenerator.getGreetingAudio(resolved.tenantId()),
                ExoMl.recordForTranscript(urls.transcript())));
    }

    /**
     * Story 6.2 (AC2-AC6): Exotel posts the transcription of the caller's recorded utterance here.
     * Signature covers CallSid+From+To+TranscriptionText (W120 for the scheme's limits).
     */
    @PostMapping(value = "/webhooks/voice/transcript", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveTranscript(
            @RequestParam(value = "CallSid", required = false, defaultValue = "") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "") String from,
            @RequestParam(value = "To", required = false, defaultValue = "") String to,
            @RequestParam(value = "TranscriptionText", required = false, defaultValue = "") String transcriptionText,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to, transcriptionText)) {
            log.warn("Exotel transcript signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }
        Gate gate = gate(request, callSid, to);
        if (gate.response != null) {
            return gate.response;
        }

        String transcript = transcriptionText.trim();
        if (transcript.isEmpty()) {
            return exoml(ExoMl.response(ExoMl.say(NOT_HEARD_PROMPT, "en"), ExoMl.recordForTranscript(urls.transcript())));
        }

        // TenantContext was set by VoiceTenantContextFilter (must precede open-in-view — see its javadoc).
        try {
            VoiceTurnResult result = exotelCallService.handleTranscript(
                    gate.route.tenantId(), callSid, CallerPhone.normalize(from), to, transcript);
            return switch (result.outcome()) {
                // Story 6.3 (AC4): hold while the owner decides; after the pause Exotel moves on to
                // the transfer-timeout handler (unless the owner bridged the call first).
                case HOLD_FOR_TRANSFER -> exoml(ExoMl.response(
                        ExoMl.say(ExotelCallService.HOLD_MESSAGE, result.languageCode()),
                        ExoMl.pause(HOLD_SECONDS),
                        ExoMl.redirect(urls.transferTimeout())));
                case NO_AGENT -> exoml(ExoMl.response(
                        ExoMl.say(ExotelCallService.NO_AGENT_MESSAGE, result.languageCode()), ExoMl.hangup()));
                case ANSWER -> exoml(ExoMl.response(
                        responseGenerator.speak(result.responseText(), result.languageCode()),
                        ExoMl.recordForTranscript(urls.transcript())));
            };
        } catch (Exception ex) {
            log.error("Voice transcript processing failed for CallSid={}: {}", callSid, ex.getMessage(), ex);
            return exoml(ExoMl.response(ExoMl.say(PROCESSING_ERROR_PROMPT, "en"), ExoMl.recordForTranscript(urls.transcript())));
        }
    }

    /**
     * Story 6.3 (AC5): reached after the 60s hold. Unless the call was actually transferred, the
     * caller is told no one is available and gets a WhatsApp follow-up.
     */
    @PostMapping(value = "/webhooks/voice/transfer-timeout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> transferTimeout(
            @RequestParam(value = "CallSid", required = false, defaultValue = "") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "") String from,
            @RequestParam(value = "To", required = false, defaultValue = "") String to,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to)) {
            log.warn("Exotel transfer-timeout signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }
        Gate gate = gate(request, callSid, to);
        if (gate.response != null) {
            return gate.response;
        }
        try {
            if (exotelCallService.handleTransferTimeout(gate.route.tenantId(), callSid, CallerPhone.normalize(from))) {
                return exoml(ExoMl.response(ExoMl.say(ExotelCallService.NO_AGENT_MESSAGE, "en"), ExoMl.hangup()));
            }
        } catch (Exception ex) {
            // Don't promise a WhatsApp follow-up we failed to arrange.
            log.error("Transfer-timeout handling failed for CallSid={}: {}", callSid, ex.getMessage(), ex);
            return exoml(ExoMl.response(ExoMl.say(TIMEOUT_ERROR_MESSAGE, "en"), ExoMl.hangup()));
        }
        return exoml(ExoMl.response());
    }

    /**
     * Story 6.2 (AC5), review decision: Exotel StatusCallback when the call ends. Records the real
     * end time and duration; a hang-up during the transfer hold becomes MISSED + follow-up.
     * Signature over CallSid+From+To+Status (same unverified scheme, W120/W121).
     */
    @PostMapping(value = "/webhooks/voice/status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callStatus(
            @RequestParam(value = "CallSid", required = false, defaultValue = "") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "") String from,
            @RequestParam(value = "To", required = false, defaultValue = "") String to,
            @RequestParam(value = "Status", required = false, defaultValue = "") String status,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        if (!signatureVerifier.verify(signature, callSid, from, to, status)) {
            log.warn("Exotel status-callback signature verification failed for CallSid={}", callSid);
            return ResponseEntity.ok().build();
        }
        Optional<ResolvedTenantVoiceRoute> route = callSid.isBlank() || to.isBlank()
                ? Optional.empty() : resolveRoute(request, to);
        if (route.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        try {
            exotelCallService.handleCallEnded(route.get().tenantId(), callSid, CallerPhone.normalize(from));
        } catch (Exception ex) {
            log.error("Status callback handling failed for CallSid={}: {}", callSid, ex.getMessage(), ex);
        }
        return ResponseEntity.ok().build();
    }

    /** Story 6.2: serves Google-TTS audio referenced by {@code <Play>}. IDs are random UUIDs with a short TTL. */
    @GetMapping("/webhooks/voice/audio/{audioId}")
    public ResponseEntity<byte[]> audio(@PathVariable String audioId) {
        return audioStore.load(audioId)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Either a usable route, or the ExoML to answer with instead. */
    private record Gate(ResolvedTenantVoiceRoute route, ResponseEntity<String> response) {
    }

    private Gate gate(HttpServletRequest request, String callSid, String to) {
        if (callSid.isBlank() || to.isBlank()) {
            log.warn("Exotel webhook missing CallSid/To");
            return new Gate(null, exoml(UNAVAILABLE_RESPONSE));
        }
        Optional<ResolvedTenantVoiceRoute> route = resolveRoute(request, to);
        if (route.isEmpty()) {
            log.warn("No tenant found for Exotel To={}", to);
            return new Gate(null, exoml(UNAVAILABLE_RESPONSE));
        }
        ResolvedTenantVoiceRoute resolved = route.get();
        if (resolved.status() != null && BLOCKED_STATUSES.contains(resolved.status())) {
            log.info("Rejecting voice call for {} tenant={}", resolved.status(), resolved.tenantId());
            return new Gate(null, exoml(UNAVAILABLE_RESPONSE));
        }
        if ("BASIC".equals(resolved.tier())) {
            return new Gate(null, exoml(BASIC_TIER_REDIRECT_RESPONSE));
        }
        return new Gate(resolved, null);
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
