package com.aireceptionist.integration.openai;

public interface OpenAIService {

    /**
     * Send a prompt to the AI model and return the response text.
     */
    String chat(String systemPrompt, String userMessage);
}
