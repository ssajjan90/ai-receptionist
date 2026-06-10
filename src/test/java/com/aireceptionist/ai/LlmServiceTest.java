package com.aireceptionist.ai;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.PromptAssembler;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.LlmService;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmServiceTest {

    @Mock AiChatPort aiChatPort;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock ConversationContextService conversationContextService;
    @Mock PromptAssembler promptAssembler;
    @Mock FallbackMessageProvider fallbackProvider;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper testObjectMapper = new ObjectMapper();

    private LlmService llmService;
    private final String tenantId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        llmService = new LlmService(aiChatPort, knowledgeBaseService, conversationContextService,
                promptAssembler, fallbackProvider, redisTemplate, testObjectMapper, 5);
    }

    @Test
    void cacheHitSkipsLlmCall() {
        when(valueOps.get(anyString())).thenReturn(
                "{\"response\":\"cached answer\",\"confidence\":0.88,\"flaggedForReview\":false}");

        AiResponseResult result = llmService.generateResponse(
                tenantId, "TestBiz", "price of iphone", "phone", Language.ENGLISH);

        assertThat(result.response()).isEqualTo("cached answer");
        assertThat(result.confidence()).isEqualTo(0.88);
        verify(aiChatPort, never()).chat(anyString(), anyString());
        verify(knowledgeBaseService, never()).search(any(), anyString());
    }

    @Test
    void cacheMissCallsLlmAndCachesResult() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(conversationContextService.getHistory(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(new ConversationTurn("customer", "hi")));
        when(knowledgeBaseService.search(any(UUID.class), anyString())).thenReturn(List.of());
        when(promptAssembler.buildSystemPrompt(anyString(), any(), any())).thenReturn("system");
        when(promptAssembler.buildUserMessage(any(), anyString())).thenReturn("user msg");
        when(aiChatPort.chat("system", "user msg"))
                .thenReturn(new AiResponseResult("iPhone is ₹72000", 0.92));

        AiResponseResult result = llmService.generateResponse(
                tenantId, "TestBiz", "price?", "phone", Language.ENGLISH);

        assertThat(result.response()).isEqualTo("iPhone is ₹72000");
        assertThat(result.confidence()).isEqualTo(0.92);
        verify(valueOps).set(anyString(), anyString(), any());
    }

    @Test
    void promptAssemblerReceivesCorrectLanguage() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(conversationContextService.getHistory(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeBaseService.search(any(UUID.class), anyString())).thenReturn(List.of());
        when(promptAssembler.buildSystemPrompt(anyString(), any(), any())).thenReturn("system");
        when(promptAssembler.buildUserMessage(any(), anyString())).thenReturn("msg");
        when(aiChatPort.chat(anyString(), anyString())).thenReturn(new AiResponseResult("ok", 0.9));

        llmService.generateResponse(tenantId, "TestBiz", "query", "phone", Language.HINGLISH);

        verify(promptAssembler).buildSystemPrompt(anyString(), any(List.class), any(Language.class));
    }

    @Test
    void fallbackProviderResponseIsLowConfidenceAndFlagged() {
        when(fallbackProvider.getFallbackResponse(anyString(), any())).thenReturn("fallback");

        AiResponseResult result = new AiResponseResult(
                fallbackProvider.getFallbackResponse(tenantId, new RuntimeException("LLM down")),
                0.0,
                true
        );

        assertThat(result.response()).isEqualTo("fallback");
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.flaggedForReview()).isTrue();
    }
}
