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

import java.util.Map;
import java.util.UUID;

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
}
