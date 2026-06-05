package com.aireceptionist.ai.adapter.out.springai;

import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls the OpenAI Chat Completions API directly. Replace with Spring AI ChatClient once
 * the spring-ai-openai-spring-boot-starter dependency is available for this Spring Boot version.
 */
@Component
public class SpringAiChatAdapter implements AiChatPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatAdapter.class);
    private static final double FALLBACK_CONFIDENCE = 0.3;
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String apiBaseUrl;

    public SpringAiChatAdapter(RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                @Value("${spring.ai.openai.api-key:}") String apiKey,
                                @Value("${spring.ai.openai.chat.options.model:gpt-4o}") String model,
                                @Value("${spring.ai.openai.base-url:https://api.openai.com}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public AiResponseResult chat(String systemPrompt, String userMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.3
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    apiBaseUrl + COMPLETIONS_PATH,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return parseOpenAiResponse(response);
        } catch (Exception ex) {
            log.warn("OpenAI API call failed: {}", ex.getMessage());
            return new AiResponseResult("I'm unable to process that right now.", FALLBACK_CONFIDENCE);
        }
    }

    @SuppressWarnings("unchecked")
    private AiResponseResult parseOpenAiResponse(Map<String, Object> response) {
        if (response == null) {
            return new AiResponseResult("No response from AI.", FALLBACK_CONFIDENCE);
        }
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new AiResponseResult("Empty response from AI.", FALLBACK_CONFIDENCE);
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");
            return parseStructuredContent(content);
        } catch (Exception ex) {
            log.debug("Failed to parse OpenAI response structure: {}", ex.getMessage());
            return new AiResponseResult("Unable to parse AI response.", FALLBACK_CONFIDENCE);
        }
    }

    private AiResponseResult parseStructuredContent(String content) {
        if (content == null || content.isBlank()) {
            return new AiResponseResult("Empty AI content.", FALLBACK_CONFIDENCE);
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                LlmResponsePayload payload = objectMapper.readValue(
                        content.substring(start, end + 1), LlmResponsePayload.class);
                if (payload.response() != null && !payload.response().isBlank()) {
                    return new AiResponseResult(payload.response(), payload.confidence());
                }
            }
        } catch (Exception ex) {
            log.debug("Structured JSON parse failed, using raw content: {}", ex.getMessage());
        }
        return new AiResponseResult(content.trim(), FALLBACK_CONFIDENCE);
    }
}
