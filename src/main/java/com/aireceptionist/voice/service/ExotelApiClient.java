package com.aireceptionist.voice.service;

import com.aireceptionist.common.exception.ExternalServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Story 6.3 (AC3): Exotel REST call-bridging. Request shape follows the story's Dev Notes
 * ({@code Calls/connect.json}, Basic auth, {@code From}=in-progress CallSid, {@code To}=owner,
 * {@code CallerId}=business number). <b>Not verified against Exotel's live API</b> — like the
 * webhook signature scheme, confirm against Exotel docs before connecting a real account.
 */
@Component
public class ExotelApiClient {

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String sid;
    private final String apiKey;
    private final String apiToken;

    public ExotelApiClient(RestTemplate restTemplate,
                           @Value("${app.exotel.api-url:https://api.exotel.com}") String apiUrl,
                           @Value("${app.exotel.sid}") String sid,
                           @Value("${app.exotel.api-key}") String apiKey,
                           @Value("${app.exotel.api-token}") String apiToken) {
        this.restTemplate = restTemplate;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.sid = sid;
        this.apiKey = apiKey;
        this.apiToken = apiToken;
    }

    @CircuitBreaker(name = "exotelApi")
    public void connectCall(String callSid, String toPhone, String callerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(apiKey, apiToken);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", callSid);
        form.add("To", toPhone);
        if (callerId != null) {
            form.add("CallerId", callerId);
        }
        try {
            restTemplate.postForObject(apiUrl + "/v1/Accounts/" + sid + "/Calls/connect.json",
                    new HttpEntity<>(form, headers), String.class);
        } catch (RestClientException ex) {
            throw new ExternalServiceException("Exotel call transfer failed", ex);
        }
    }
}
