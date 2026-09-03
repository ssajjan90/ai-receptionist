package com.aireceptionist.voice.api;

import com.aireceptionist.tenant.port.in.ResolveTenantByBusinessPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantVoiceRoute;
import com.aireceptionist.voice.event.VoiceCallReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Story 6.1 (AC1-AC5). Exotel sends {@code CallSid}/{@code From}/{@code To}/{@code Direction}/
 * {@code Status} as form params (not JSON) — mirrors {@code TwilioWebhookController}'s
 * {@code @RequestParam} style rather than {@code WhatsAppWebhookController}'s raw-JSON-body style.
 * NOT extending {@code VersionedRestController} (Dev Notes) — Exotel calls a fixed, unversioned
 * ExoML URL, same reasoning as the other webhook controllers in this codebase.
 *
 * <p>Signature scheme: Exotel's exact HMAC canonicalization isn't in this repo's Dev Notes beyond
 * "HMAC-SHA1 with the shared secret" — since the request is form-encoded (no single raw body to
 * hash, unlike WhatsApp's JSON payload), this signs the concatenation of the five documented
 * fields in a fixed order (CallSid+From+To+Direction+Status), hex-encoded, matching this
 * codebase's WhatsApp HMAC verification style. Revisit against Exotel's actual API docs before
 * connecting a real Exotel account.
 *
 * <p>AC5 (voice/WhatsApp failure independence): satisfied by construction — this controller shares
 * no service, repository, or queue with the whatsapp module.
 */
@RestController
public class VoiceWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Exotel-Signature";

    private static final String UNAVAILABLE_RESPONSE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Response><Play>We're unable to process your call right now. Please try again later.</Play>"
                    + "<Hangup/></Response>";

    private static final String BASIC_TIER_REDIRECT_RESPONSE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Response><Play>Please visit our WhatsApp at +91XXXXXXXXXX for assistance.</Play>"
                    + "<Hangup/></Response>";

    private static final String AI_HANDOFF_RESPONSE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Response><Record action=\"/webhooks/voice/transcript\" maxLength=\"30\"/></Response>";

    private final String sharedSecret;
    private final ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase;
    private final ApplicationEventPublisher eventPublisher;

    public VoiceWebhookController(@Value("${app.exotel.shared-secret}") String sharedSecret,
                                  ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase,
                                  ApplicationEventPublisher eventPublisher) {
        this.sharedSecret = sharedSecret;
        this.resolveTenantByBusinessPhoneUseCase = resolveTenantByBusinessPhoneUseCase;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping(value = "/webhooks/voice", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveCall(
            @RequestParam("CallSid") String callSid,
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestParam(value = "Direction", required = false, defaultValue = "") String direction,
            @RequestParam(value = "Status", required = false, defaultValue = "") String status,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        if (!verifySignature(callSid, from, to, direction, status, signature)) {
            log.warn("Exotel webhook signature verification failed for CallSid={}", callSid);
            return exoml(UNAVAILABLE_RESPONSE);
        }

        Optional<ResolvedTenantVoiceRoute> route = resolveTenantByBusinessPhoneUseCase.resolveByBusinessPhone(to);
        if (route.isEmpty()) {
            log.warn("No tenant found for Exotel To={}", to);
            return exoml(UNAVAILABLE_RESPONSE);
        }

        ResolvedTenantVoiceRoute resolved = route.get();
        if ("BASIC".equals(resolved.tier())) {
            return exoml(BASIC_TIER_REDIRECT_RESPONSE);
        }

        eventPublisher.publishEvent(new VoiceCallReceivedEvent(resolved.tenantId().toString(), callSid, from, to));
        return exoml(AI_HANDOFF_RESPONSE);
    }

    private ResponseEntity<String> exoml(String xml) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml);
    }

    private boolean verifySignature(String callSid, String from, String to, String direction, String status,
                                     String signature) {
        if (signature == null || signature.isBlank() || sharedSecret == null || sharedSecret.isBlank()) {
            return false;
        }
        try {
            String canonical = nullToEmpty(callSid) + nullToEmpty(from) + nullToEmpty(to)
                    + nullToEmpty(direction) + nullToEmpty(status);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String computed = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            log.warn("Unable to verify Exotel webhook signature: {}", ex.getMessage());
            return false;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
