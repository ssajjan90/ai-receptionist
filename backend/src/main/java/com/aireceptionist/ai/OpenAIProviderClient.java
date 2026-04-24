package com.aireceptionist.ai;

import com.aireceptionist.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "openai")
public class OpenAIProviderClient implements AIProviderClient {

    private static final String DEFAULT_REPLY = "Thank you for contacting us. We will get back to you shortly.";

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final Integer maxTokens;
    private final Double temperature;

    public OpenAIProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${ai.openai.api-key:}") String apiKey,
            @Value("${ai.openai.model:gpt-4o-mini}") String model,
            @Value("${ai.openai.max-tokens:300}") Integer maxTokens,
            @Value("${ai.openai.temperature:0.3}") Double temperature
    ) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public String generateResponse(AIRequest request) {
        validateRequest(request);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI provider is enabled but no API key is configured.");
            return DEFAULT_REPLY;
        }

        String systemPrompt = buildPrompt(request);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", request.getCustomerMessage())
                    )
            );

            Map<?, ?> response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String content = extractContent(response);
            return content == null || content.isBlank() ? DEFAULT_REPLY : content.trim();
        } catch (Exception ex) {
            log.error("OpenAI API call failed.", ex);
            return DEFAULT_REPLY;
        }
    }

    private void validateRequest(AIRequest request) {
        if (request == null) {
            throw new BadRequestException("AI request is required.");
        }
        if (request.getCustomerMessage() == null || request.getCustomerMessage().isBlank()) {
            throw new BadRequestException("Customer message is required.");
        }
    }

    private String buildPrompt(AIRequest request) {
        String tenantDetails = String.format(
                "Tenant Name: %s%nIndustry: %s%nWorking Hours: %s",
                valueOrDefault(request.getTenantName(), "N/A"),
                valueOrDefault(request.getIndustry(), "N/A"),
                valueOrDefault(request.getWorkingHours(), "N/A")
        );

        String knowledgeBase = valueOrDefault(request.getKnowledgeBase(), "No knowledge base provided.");

        return "You are an AI receptionist. Respond concisely and helpfully based on the tenant context."
                + System.lineSeparator() + System.lineSeparator()
                + "Tenant Details:" + System.lineSeparator()
                + tenantDetails + System.lineSeparator() + System.lineSeparator()
                + "Knowledge Base:" + System.lineSeparator()
                + knowledgeBase + System.lineSeparator() + System.lineSeparator()
                + "Use the customer message to provide the best possible answer."
                + " If the answer is not in the knowledge base, ask a clarifying follow-up question.";
    }

    private String extractContent(Map<?, ?> response) {
        if (response == null) {
            return null;
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return null;
        }

        Object messageObj = choiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            return null;
        }

        Object contentObj = messageMap.get("content");
        return contentObj instanceof String ? (String) contentObj : null;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
