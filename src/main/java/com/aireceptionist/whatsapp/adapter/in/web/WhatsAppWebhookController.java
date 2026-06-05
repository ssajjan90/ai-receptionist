package com.aireceptionist.whatsapp.adapter.in.web;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.whatsapp.adapter.in.web.dto.WhatsAppWebhookPayload;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final String verifyToken;
    private final String appSecret;
    private final ObjectMapper objectMapper;
    private final ResolveTenantByWhatsAppPhoneUseCase resolveTenantByWhatsAppPhoneUseCase;
    private final ApplicationEventPublisher eventPublisher;

    public WhatsAppWebhookController(@Value("${app.whatsapp.verify-token}") String verifyToken,
                                     @Value("${app.whatsapp.app-secret}") String appSecret,
                                     ObjectMapper objectMapper,
                                     ResolveTenantByWhatsAppPhoneUseCase resolveTenantByWhatsAppPhoneUseCase,
                                     ApplicationEventPublisher eventPublisher) {
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
        this.objectMapper = objectMapper;
        this.resolveTenantByWhatsAppPhoneUseCase = resolveTenantByWhatsAppPhoneUseCase;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyWebhook(@RequestParam("hub.mode") String mode,
                                                @RequestParam("hub.challenge") String challenge,
                                                @RequestParam("hub.verify_token") String token) {
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> receiveWebhook(HttpServletRequest request, @RequestBody byte[] rawBody) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (!verifySignature(rawBody, signature)) {
            log.warn("WhatsApp webhook HMAC verification failed");
            return ResponseEntity.ok().build();
        }

        try {
            publishInboundMessages(objectMapper.readValue(rawBody, WhatsAppWebhookPayload.class));
        } catch (Exception ex) {
            log.warn("Unable to parse WhatsApp webhook payload: {}", ex.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private void publishInboundMessages(WhatsAppWebhookPayload payload) {
        for (WhatsAppWebhookPayload.Entry entry : safeList(payload.entry())) {
            for (WhatsAppWebhookPayload.Change change : safeList(entry.changes())) {
                WhatsAppWebhookPayload.Value value = change.value();
                if (value == null || value.metadata() == null) {
                    continue;
                }
                String phoneNumberId = value.metadata().phoneNumberId();
                var route = resolveTenantByWhatsAppPhoneUseCase.resolveByPhoneNumberId(phoneNumberId);
                if (route.isEmpty()) {
                    log.warn("No tenant found for WhatsApp phoneNumberId={}", phoneNumberId);
                    continue;
                }
                ResolvedTenantWhatsAppRoute resolved = route.get();
                TenantContext.setCurrentTenant(resolved.tenantId().toString());
                try {
                    for (WhatsAppWebhookPayload.Message message : safeList(value.messages())) {
                        String messageText = message.text() == null ? null : message.text().body();
                        Instant now = Instant.now();
                        if (resolved.ownerPhone() != null && resolved.ownerPhone().equals(message.from())) {
                            eventPublisher.publishEvent(new OwnerCommandReceivedEvent(
                                    resolved.tenantId().toString(), messageText, message.from(), now));
                        } else {
                            eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                                    resolved.tenantId(), message.id(), message.from(), messageText, phoneNumberId, now));
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
            }
        }
    }

    private boolean verifySignature(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank() || appSecret == null || appSecret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            log.warn("Unable to verify WhatsApp webhook signature: {}", ex.getMessage());
            return false;
        }
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }
}
