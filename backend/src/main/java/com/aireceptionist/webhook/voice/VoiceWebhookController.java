package com.aireceptionist.webhook.voice;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.receptionist.AIReceptionistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/voice")
@RequiredArgsConstructor
public class VoiceWebhookController {

    private static final String PROMPT_TO_SPEAK = "I didn't catch that. Please tell me how I can help you.";

    private final AIReceptionistService aiReceptionistService;

    @Value("${voice.default-tenant-id:1}")
    private Long defaultTenantId;

    @PostMapping(value = "/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncoming(
            @RequestParam("From") String from,
            @RequestParam("To") String to,
            @RequestParam(value = "SpeechResult", required = false) String speechResult
    ) {
        if (speechResult == null || speechResult.isBlank()) {
            log.info("Voice webhook received without SpeechResult. from={}, to={}", from, to);
            return ResponseEntity.ok(buildTwiMlResponse(PROMPT_TO_SPEAK));
        }

        InboundMessageRequest inboundMessageRequest = new InboundMessageRequest();
        inboundMessageRequest.setTenantId(defaultTenantId);
        inboundMessageRequest.setChannel(CommunicationChannel.VOICE_CALL);
        inboundMessageRequest.setCustomerPhone(from);
        inboundMessageRequest.setMessage(speechResult);

        OutboundMessageResponse response = aiReceptionistService.processInboundMessage(inboundMessageRequest);
        String responseMessage = response.getResponseMessage();

        log.info("Processed voice webhook. from={}, to={}, tenantId={}, speech='{}', aiResponse='{}'",
                from,
                to,
                defaultTenantId,
                speechResult,
                responseMessage);

        return ResponseEntity.ok(buildTwiMlResponse(responseMessage));
    }

    private String buildTwiMlResponse(String message) {
        String safeMessage = escapeXml(message == null || message.isBlank() ? PROMPT_TO_SPEAK : message);
        return "<Response><Say>" + safeMessage + "</Say></Response>";
    }

    private String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
