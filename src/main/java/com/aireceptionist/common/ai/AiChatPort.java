package com.aireceptionist.common.ai;

public interface AiChatPort {

    AiResponseResult chat(String systemPrompt, String userMessage);
}
