package com.aireceptionist.billing.api;

import com.aireceptionist.billing.service.RazorpayWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 7.2 (AC2, AC3): Razorpay webhook. Raw bytes for HMAC verification; always HTTP 200 so
 * Razorpay stops retrying (architecture: webhook responses). Unversioned, under the
 * already-permitAll {@code /webhooks/**}.
 */
@RestController
public class BillingWebhookController {

    private final RazorpayWebhookService webhookService;

    public BillingWebhookController(RazorpayWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks/billing")
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] rawBody,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        webhookService.processWebhook(rawBody == null ? new byte[0] : rawBody, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
