package com.aireceptionist.whatsapp.service;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.tenant.port.in.GetTenantPhoneNumberIdUseCase;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link #sendMessage} is free-form {@code type: text} — Meta only allows this for
 * business-initiated sends inside the 24h customer-service session window; outside it, Meta
 * requires a pre-approved message template (W70). {@link #sendTemplateMessage} exists so
 * proactive callers (daily summary, admin notify/broadcast, suspension notices) can switch to a
 * real template once one is approved in Meta Business Manager — that approval is a business
 * step outside this codebase, not something this method alone can satisfy.
 */
@Service
public class WhatsAppNotificationService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationService.class);

    private final RestTemplate restTemplate;
    private final WhatsAppQueueService queueService;
    private final GetTenantPhoneNumberIdUseCase phoneNumberIdResolver;
    private final String apiUrl;
    private final String accessToken;

    public WhatsAppNotificationService(RestTemplate restTemplate,
                                       WhatsAppQueueService queueService,
                                       GetTenantPhoneNumberIdUseCase phoneNumberIdResolver,
                                       @Value("${app.whatsapp.api-url}") String apiUrl,
                                       @Value("${app.whatsapp.access-token}") String accessToken) {
        this.restTemplate = restTemplate;
        this.queueService = queueService;
        this.phoneNumberIdResolver = phoneNumberIdResolver;
        this.apiUrl = apiUrl;
        this.accessToken = accessToken;
    }

    @CircuitBreaker(name = "whatsAppNotificationService", fallbackMethod = "sendMessageFallback")
    public void sendMessage(String tenantId, String recipientPhone, String message) {
        String phoneNumberId = phoneNumberIdResolver.findPhoneNumberId(UUID.fromString(tenantId))
                .orElseThrow(() -> new ExternalServiceException("No phoneNumberId found for tenant " + tenantId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", recipientPhone,
                "type", "text",
                "text", Map.of("body", message)
        );

        restTemplate.postForObject(
                apiUrl + "/" + phoneNumberId + "/messages",
                new HttpEntity<>(body, headers),
                Void.class
        );
        log.debug("WhatsApp message sent to {} (tenant={})", recipientPhone, tenantId);
    }

    void sendMessageFallback(String tenantId, String recipientPhone, String message, Throwable cause) {
        log.warn("WhatsApp API unavailable for tenant {}, message queued for retry", tenantId, cause);
        queueService.enqueueMessage(tenantId, recipientPhone, message);
    }

    /**
     * Sends a pre-approved WhatsApp template message — the only Meta-compliant way to reach a
     * customer outside the 24h customer-service session window. {@code templateName} must
     * already be approved in Meta Business Manager with a body that accepts exactly
     * {@code bodyParams.size()} text parameters, in order.
     */
    @CircuitBreaker(name = "whatsAppNotificationService", fallbackMethod = "sendTemplateMessageFallback")
    public void sendTemplateMessage(String tenantId, String recipientPhone, String templateName,
                                     String languageCode, List<String> bodyParams) {
        String phoneNumberId = phoneNumberIdResolver.findPhoneNumberId(UUID.fromString(tenantId))
                .orElseThrow(() -> new ExternalServiceException("No phoneNumberId found for tenant " + tenantId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        List<Map<String, Object>> parameters = bodyParams.stream()
                .map(param -> Map.<String, Object>of("type", "text", "text", param))
                .toList();

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", recipientPhone,
                "type", "template",
                "template", Map.of(
                        "name", templateName,
                        "language", Map.of("code", languageCode),
                        "components", List.of(Map.of("type", "body", "parameters", parameters))
                )
        );

        restTemplate.postForObject(
                apiUrl + "/" + phoneNumberId + "/messages",
                new HttpEntity<>(body, headers),
                Void.class
        );
        log.debug("WhatsApp template '{}' sent to {} (tenant={})", templateName, recipientPhone, tenantId);
    }

    // Template sends need their own retry story (re-queueing as free text would silently swap a
    // compliant template send for the exact non-compliant send this method exists to avoid), so
    // this fallback only logs rather than reusing WhatsAppQueueService like sendMessageFallback.
    void sendTemplateMessageFallback(String tenantId, String recipientPhone, String templateName,
                                      String languageCode, List<String> bodyParams, Throwable cause) {
        log.warn("WhatsApp template API unavailable for tenant {} (template={})", tenantId, templateName, cause);
    }
}
