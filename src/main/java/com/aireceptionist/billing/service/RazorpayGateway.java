package com.aireceptionist.billing.service;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Story 7.2 (AC1): creates a Razorpay subscription and returns its hosted checkout link
 * ({@code short_url}) — card data never touches this app (NFR14).
 *
 * <p>Deviation from Task 1: Razorpay's REST API is called directly instead of adding
 * {@code razorpay-java}. That SDK pins okhttp 3.10.0 (2018, with known CVEs) and we need a single
 * call; the REST shape (POST /v1/subscriptions, Basic auth key_id:key_secret) is Razorpay's
 * documented API. {@code notes.tenant_id} lets the webhook map events back to the tenant without
 * a cross-tenant lookup (subscriptions is RLS-protected).
 */
@Component
public class RazorpayGateway {

    /** Razorpay requires a cycle count; 10 years of monthly renewals ≈ open-ended. */
    static final int TOTAL_COUNT = 120;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String keyId;
    private final String keySecret;

    public RazorpayGateway(RestTemplate restTemplate, ObjectMapper objectMapper,
                           @Value("${app.razorpay.api-url:https://api.razorpay.com}") String apiUrl,
                           @Value("${app.razorpay.key-id}") String keyId,
                           @Value("${app.razorpay.key-secret}") String keySecret) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    public record CreatedSubscription(String id, String checkoutUrl) {
    }

    @CircuitBreaker(name = "razorpayApi")
    public CreatedSubscription createSubscription(String planId, UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(keyId, keySecret);
        Map<String, Object> body = Map.of(
                "plan_id", planId,
                "total_count", TOTAL_COUNT,
                "quantity", 1,
                "customer_notify", 1,
                "notes", Map.of("tenant_id", tenantId.toString()));
        try {
            String json = restTemplate.postForObject(apiUrl + "/v1/subscriptions", new HttpEntity<>(body, headers), String.class);
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json);
            String id = node.path("id").asText(null);
            String shortUrl = node.path("short_url").asText(null);
            if (id == null || shortUrl == null) {
                throw new ExternalServiceException("Razorpay subscription response missing id/short_url");
            }
            return new CreatedSubscription(id, shortUrl);
        } catch (RestClientException | java.io.IOException ex) {
            throw new ExternalServiceException("Razorpay subscription creation failed", ex);
        }
    }
}
