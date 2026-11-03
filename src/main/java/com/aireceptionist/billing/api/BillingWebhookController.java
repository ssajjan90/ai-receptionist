package com.aireceptionist.billing.api;

import com.aireceptionist.billing.service.RazorpayWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Story 7.2 (AC2, AC3): Razorpay webhook, unversioned under the permitAll {@code /webhooks/**}.
 * HTTP 200 for processed, duplicate, ignored and badly-signed events (Razorpay stops retrying);
 * HTTP 500 only when processing failed, so Razorpay redelivers instead of the event being lost
 * (code review). The body is capped before it is read so unauthenticated callers can't make the
 * server buffer and HMAC arbitrarily large payloads.
 */
@RestController
public class BillingWebhookController {

    static final int MAX_BODY_BYTES = 256 * 1024;

    private final RazorpayWebhookService webhookService;

    public BillingWebhookController(RazorpayWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks/billing")
    public ResponseEntity<Void> receive(HttpServletRequest request,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature)
            throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        RazorpayWebhookService.Result result = webhookService.processWebhook(body, signature);
        return result == RazorpayWebhookService.Result.FAILED
                ? ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
                : ResponseEntity.ok().build();
    }
}
