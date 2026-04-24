package com.aireceptionist.integration.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
public class WhatsAppCloudApiClient {

    private final WebClient webClient;

    public WhatsAppCloudApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${whatsapp.api.base-url:https://graph.facebook.com}") String baseUrl
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    public boolean sendTextMessage(String phoneNumberId, String accessToken, String to, String message) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.error("Unable to send WhatsApp message: missing phoneNumberId.");
            return false;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.error("Unable to send WhatsApp message: missing access token for phoneNumberId={}", phoneNumberId);
            return false;
        }
        if (to == null || to.isBlank()) {
            log.error("Unable to send WhatsApp message: missing destination number for phoneNumberId={}", phoneNumberId);
            return false;
        }
        if (message == null || message.isBlank()) {
            log.warn("Skipping WhatsApp send for phoneNumberId={} to={} due to blank message.", phoneNumberId, to);
            return false;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "messaging_product", "whatsapp",
                    "to", to,
                    "type", "text",
                    "text", Map.of("body", message)
            );

            webClient.post()
                    .uri("/v20.0/{phoneNumberId}/messages", phoneNumberId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("WhatsApp Cloud API message sent successfully. phoneNumberId={}, to={}", phoneNumberId, to);
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to send WhatsApp Cloud API message. phoneNumberId={}, to={}, reason={}",
                    phoneNumberId,
                    to,
                    e.getMessage(),
                    e
            );
            return false;
        }
    }
}
