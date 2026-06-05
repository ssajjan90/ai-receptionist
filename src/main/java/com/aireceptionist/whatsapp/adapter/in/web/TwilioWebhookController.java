package com.aireceptionist.whatsapp.adapter.in.web;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Twilio WhatsApp sandbox webhook — for local dev/testing only.
 * Twilio sends application/x-www-form-urlencoded, not Meta JSON.
 * No HMAC verification: do not expose this endpoint in production.
 */
@RestController
@RequestMapping("/webhooks/whatsapp/twilio")
public class TwilioWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioWebhookController.class);

    private final ResolveTenantByWhatsAppPhoneUseCase resolveTenantByWhatsAppPhoneUseCase;
    private final ApplicationEventPublisher eventPublisher;

    public TwilioWebhookController(ResolveTenantByWhatsAppPhoneUseCase resolveTenantByWhatsAppPhoneUseCase,
                                   ApplicationEventPublisher eventPublisher) {
        this.resolveTenantByWhatsAppPhoneUseCase = resolveTenantByWhatsAppPhoneUseCase;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receive(
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestParam(value = "Body", required = false, defaultValue = "") String body,
            @RequestParam("MessageSid") String messageSid) {

        // Twilio sends "whatsapp:+919876543210" — strip the prefix for lookup/storage
        String fromPhone = stripWhatsAppPrefix(from);
        String toPhone = stripWhatsAppPrefix(to);

        var route = resolveTenantByWhatsAppPhoneUseCase.resolveByPhoneNumberId(toPhone);
        if (route.isEmpty()) {
            log.warn("No tenant found for Twilio To={}", to);
            return ResponseEntity.ok().build();
        }

        ResolvedTenantWhatsAppRoute resolved = route.get();
        TenantContext.setCurrentTenant(resolved.tenantId().toString());

        if (resolved.ownerPhone() != null && resolved.ownerPhone().equals(fromPhone)) {
            eventPublisher.publishEvent(new OwnerCommandReceivedEvent(
                    resolved.tenantId().toString(), body, fromPhone, Instant.now()));
        } else {
            eventPublisher.publishEvent(new InboundWhatsAppMessageEvent(
                    resolved.tenantId(), messageSid, fromPhone, body, toPhone, Instant.now()));
        }

        return ResponseEntity.ok().build();
    }

    private String stripWhatsAppPrefix(String address) {
        if (address == null) return null;
        // "whatsapp:+14155238886" → "14155238886"
        return address.replaceFirst("(?i)^whatsapp:\\+?", "");
    }
}
