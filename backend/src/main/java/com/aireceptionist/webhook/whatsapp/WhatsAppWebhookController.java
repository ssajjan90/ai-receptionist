package com.aireceptionist.webhook.whatsapp;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.service.TenantChannelConfigService;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.receptionist.AIReceptionistService;
import com.aireceptionist.integration.whatsapp.WhatsAppCloudApiClient;
import com.aireceptionist.webhook.whatsapp.dto.WhatsAppWebhookRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "WhatsApp Webhooks", description = "Meta WhatsApp webhook verification and inbound message handling")
public class WhatsAppWebhookController {

    private final String verifyToken;
    private final AIReceptionistService aiReceptionistService;
    private final TenantChannelConfigService tenantChannelConfigService;
    private final Long defaultTenantId;
    private final WhatsAppCloudApiClient whatsAppCloudApiClient;

    public WhatsAppWebhookController(
            @Value("${whatsapp.verify-token}") String verifyToken,
            @Value("${whatsapp.default-tenant-id:1}") Long defaultTenantId,
            AIReceptionistService aiReceptionistService,
            TenantChannelConfigService tenantChannelConfigService,
            WhatsAppCloudApiClient whatsAppCloudApiClient
    ) {
        this.verifyToken = verifyToken;
        this.defaultTenantId = defaultTenantId;
        this.aiReceptionistService = aiReceptionistService;
        this.tenantChannelConfigService = tenantChannelConfigService;
        this.whatsAppCloudApiClient = whatsAppCloudApiClient;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Verify WhatsApp webhook callback")
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
    @Operation(summary = "Handle inbound WhatsApp webhook event")
    public ResponseEntity<Void> handleWebhook(@RequestBody WhatsAppWebhookRequest request) {
        ParsedWebhookPayload payload = parseWebhookPayload(request);

        if (payload == null) {
            log.info("WhatsApp webhook received with no message payload to process.");
            return ResponseEntity.ok().build();
        }

        var tenantChannelConfig = tenantChannelConfigService
                .findByChannelAndExternalPhoneNumberId(CommunicationChannel.WHATSAPP, payload.phoneNumberId());

        Long resolvedTenantId = tenantChannelConfig
                .map(config -> config.getTenantId())
                .orElse(defaultTenantId);

        InboundMessageRequest inboundMessageRequest = new InboundMessageRequest();
        inboundMessageRequest.setTenantId(resolvedTenantId);
        inboundMessageRequest.setChannel(CommunicationChannel.WHATSAPP);
        inboundMessageRequest.setCustomerPhone(payload.from());
        inboundMessageRequest.setMessage(payload.text());
        inboundMessageRequest.setExternalMessageId(payload.messageId());
        inboundMessageRequest.setMetadata(Map.of("phoneNumberId", payload.phoneNumberId()));

        OutboundMessageResponse response = aiReceptionistService.processInboundMessage(inboundMessageRequest);
        log.info(
                "Processed WhatsApp inbound message. messageId={}, from={}, phoneNumberId={}, tenantId={}, aiResponse='{}'",
                payload.messageId(),
                payload.from(),
                payload.phoneNumberId(),
                resolvedTenantId,
                response.getResponseMessage()
        );

        if (response.getResponseMessage() != null && !response.getResponseMessage().isBlank()) {
            if (tenantChannelConfig.isPresent() && tenantChannelConfig.get().getAccessToken() != null
                    && !tenantChannelConfig.get().getAccessToken().isBlank()) {
                boolean sent = whatsAppCloudApiClient.sendTextMessage(
                        payload.phoneNumberId(),
                        tenantChannelConfig.get().getAccessToken(),
                        payload.from(),
                        response.getResponseMessage()
                );
                if (!sent) {
                    log.warn(
                            "AI response generated but outbound WhatsApp message failed. messageId={}, from={}, phoneNumberId={}, tenantId={}",
                            payload.messageId(),
                            payload.from(),
                            payload.phoneNumberId(),
                            resolvedTenantId
                    );
                }
            } else {
                log.warn(
                        "AI response generated but no enabled WhatsApp channel config/access token for phoneNumberId={}. tenantId={}",
                        payload.phoneNumberId(),
                        resolvedTenantId
                );
            }
        } else {
            log.warn(
                    "AI response is blank; skipping outbound WhatsApp message. messageId={}, from={}, phoneNumberId={}, tenantId={}",
                    payload.messageId(),
                    payload.from(),
                    payload.phoneNumberId(),
                    resolvedTenantId
            );
        }

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
