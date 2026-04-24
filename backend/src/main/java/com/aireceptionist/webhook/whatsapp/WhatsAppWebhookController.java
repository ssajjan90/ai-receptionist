package com.aireceptionist.webhook.whatsapp;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.receptionist.AIReceptionistService;
import com.aireceptionist.webhook.whatsapp.dto.WhatsAppWebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private final String verifyToken;
    private final AIReceptionistService aiReceptionistService;
    private final Long defaultTenantId;

    public WhatsAppWebhookController(
            @Value("${whatsapp.verify-token}") String verifyToken,
            @Value("${whatsapp.default-tenant-id:1}") Long defaultTenantId,
            AIReceptionistService aiReceptionistService
    ) {
        this.verifyToken = verifyToken;
        this.defaultTenantId = defaultTenantId;
        this.aiReceptionistService = aiReceptionistService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.challenge") String challenge,
            @RequestParam("hub.verify_token") String token
    ) {
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(challenge);
        }

        return ResponseEntity.status(403)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Forbidden");
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(@RequestBody WhatsAppWebhookRequest request) {
        ParsedWebhookPayload payload = parseWebhookPayload(request);

        if (payload == null) {
            log.info("WhatsApp webhook received with no message payload to process.");
            return ResponseEntity.ok().build();
        }

        InboundMessageRequest inboundMessageRequest = new InboundMessageRequest();
        inboundMessageRequest.setTenantId(defaultTenantId);
        inboundMessageRequest.setChannel(CommunicationChannel.WHATSAPP);
        inboundMessageRequest.setCustomerPhone(payload.from());
        inboundMessageRequest.setMessage(payload.text());
        inboundMessageRequest.setExternalMessageId(payload.messageId());
        inboundMessageRequest.setMetadata(Map.of("phoneNumberId", payload.phoneNumberId()));

        OutboundMessageResponse response = aiReceptionistService.processInboundMessage(inboundMessageRequest);
        log.info(
                "Processed WhatsApp inbound message. messageId={}, from={}, phoneNumberId={}, aiResponse='{}'",
                payload.messageId(),
                payload.from(),
                payload.phoneNumberId(),
                response.getResponseMessage()
        );

        return ResponseEntity.ok().build();
    }

    private ParsedWebhookPayload parseWebhookPayload(WhatsAppWebhookRequest request) {
        if (request == null || request.getEntry() == null) {
            return null;
        }

        for (WhatsAppWebhookRequest.Entry entry : request.getEntry()) {
            List<WhatsAppWebhookRequest.Change> changes = entry.getChanges();
            if (changes == null) {
                continue;
            }

            for (WhatsAppWebhookRequest.Change change : changes) {
                WhatsAppWebhookRequest.Value value = change.getValue();
                if (value == null || value.getMessages() == null || value.getMessages().isEmpty()) {
                    continue;
                }

                WhatsAppWebhookRequest.Message message = value.getMessages().get(0);
                if (message == null || message.getText() == null || message.getText().getBody() == null) {
                    continue;
                }

                String from = message.getFrom();
                String messageText = message.getText().getBody();
                String messageId = message.getId();
                String phoneNumberId = value.getMetadata() == null ? null : value.getMetadata().getPhoneNumberId();

                if (from != null && messageText != null && phoneNumberId != null) {
                    return new ParsedWebhookPayload(from, messageText, messageId, phoneNumberId);
                }
            }
        }

        return null;
    }

    private record ParsedWebhookPayload(String from, String text, String messageId, String phoneNumberId) {
    }
}
