package com.aireceptionist.integration.openai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT integration.
 *
 * When OPENAI_API_KEY is set to a real key this will call the actual API.
 * While the key is the default placeholder value the service falls back to
 * a clearly-labelled mock response so the rest of the application remains
 * fully functional during development without a paid key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIServiceImpl implements OpenAIService {

    private static final String PLACEHOLDER_KEY = "placeholder-key";

    private final WebClient openAiWebClient;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.api.max-tokens:500}")
    private int maxTokens;

    @Value("${openai.api.temperature:0.7}")
    private double temperature;

    @Override
    public String chat(String systemPrompt, String userMessage) {
        if (PLACEHOLDER_KEY.equals(apiKey)) {
            log.warn("OpenAI API key not configured — returning mock response");
            return buildMockResponse(userMessage);
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            Map<?, ?> response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null) {
                List<?> choices = (List<?>) response.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                    Map<?, ?> message = (Map<?, ?>) choice.get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
            }
        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage(), e);
        }

        return "I'm sorry, I'm having trouble connecting to my knowledge system right now. " +
               "Please call us directly or try again shortly.";
    }

    private String buildMockResponse(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("appointment") || lower.contains("book") || lower.contains("schedule")) {
            return "[MOCK] I'd be happy to help you book an appointment! " +
                   "Could you please share your preferred date, time, and the service you need, " +
                   "along with your name and phone number?";
        }
        if (lower.contains("price") || lower.contains("cost") || lower.contains("fee") || lower.contains("charge")) {
            return "[MOCK] For pricing information, please contact our reception team directly " +
                   "or visit our website. We'll be happy to provide a detailed quote.";
        }
        if (lower.contains("hour") || lower.contains("open") || lower.contains("close") || lower.contains("timing")) {
            return "[MOCK] Our working hours are as configured for this business. " +
                   "Please check with us for the latest schedule.";
        }
        return "[MOCK] Thank you for reaching out! How can I assist you today? " +
               "I can help with appointments, information about our services, or connect you with our team.";
    }
}
