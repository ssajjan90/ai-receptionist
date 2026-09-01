package com.aireceptionist.ai.service;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponse;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

// Relocated from common.ai (code review of story 5-1's AdminModuleTest, 2026-09-01): this
// orchestrates AI response generation using ai.service's own collaborators (ConversationContextService,
// PromptAssembler, Language) plus knowledgebase — it's ai-domain orchestration, not shared common
// infrastructure. Living in common.ai while depending on ai.service created a module cycle
// (ai -> common via SpringAiChatAdapter implementing AiChatPort, common -> ai via this class),
// which Spring Modulith flags as a violation. common.ai now holds only the port/DTO contracts
// (AiChatPort, AiResponseResult, AiResponse, TenantNamePort, TenantOwnerPhonePort) that other
// modules implement/consume — no orchestration logic depending on ai.service. See deferred W82.
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final String CACHE_PREFIX = "tenant:";
    private static final String CACHE_QUERY_INFIX = ":query:";
    private static final int MAX_HISTORY_TURNS = 5;

    private final AiChatPort aiChatPort;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ConversationContextService conversationContextService;
    private final PromptAssembler promptAssembler;
    private final FallbackMessageProvider fallbackProvider;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration responseCacheTtl;

    public LlmService(AiChatPort aiChatPort,
                      KnowledgeBaseService knowledgeBaseService,
                      ConversationContextService conversationContextService,
                      PromptAssembler promptAssembler,
                      FallbackMessageProvider fallbackProvider,
                      StringRedisTemplate redisTemplate,
                      ObjectMapper objectMapper,
                      @Value("${app.cache.response-ttl-minutes:5}") int cacheTtlMinutes) {
        this.aiChatPort = aiChatPort;
        this.knowledgeBaseService = knowledgeBaseService;
        this.conversationContextService = conversationContextService;
        this.promptAssembler = promptAssembler;
        this.fallbackProvider = fallbackProvider;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.responseCacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    @AiResponse(eventType = "QUERY_RESPONSE")
    @CircuitBreaker(name = "llmService", fallbackMethod = "generateResponseFallback")
    public AiResponseResult generateResponse(String tenantId, String businessName, String query,
                                             String customerPhone, Language language) {
        String cacheKey = CACHE_PREFIX + tenantId + CACHE_QUERY_INFIX
                + sha256(query.toLowerCase().trim() + "|" + language.toLangCode());
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("LLM cache hit for tenant={}", tenantId);
            try {
                return objectMapper.readValue(cached, AiResponseResult.class);
            } catch (Exception ex) {
                log.debug("Cache deserialization failed, re-querying LLM: {}", ex.getMessage());
            }
        }

        List<ConversationTurn> history = customerPhone != null
                ? conversationContextService.getHistory(tenantId, customerPhone, MAX_HISTORY_TURNS)
                : List.of();

        List<KnowledgeEntry> kbContext = knowledgeBaseService.search(UUID.fromString(tenantId), query);

        String systemPrompt = promptAssembler.buildSystemPrompt(businessName, kbContext, language);
        String userMessage = promptAssembler.buildUserMessage(history, query);

        AiResponseResult result = aiChatPort.chat(systemPrompt, userMessage);
        log.debug("LLM response generated for tenant={}, confidence={}", tenantId, result.confidence());

        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), responseCacheTtl);
        } catch (Exception ex) {
            log.debug("Failed to cache LLM response for tenant={}: {}", tenantId, ex.getMessage());
        }
        return result;
    }

    protected AiResponseResult generateResponseFallback(String tenantId, String businessName, String query,
                                                        String customerPhone, Language language,
                                                        Throwable cause) {
        return new AiResponseResult(fallbackProvider.getFallbackResponse(tenantId, cause), 0.0, true);
    }

    @AiResponse(eventType = "EMPATHY_RESPONSE")
    @CircuitBreaker(name = "llmService", fallbackMethod = "generateEmpathyResponseFallback")
    public AiResponseResult generateEmpathyResponse(String tenantId, String businessName, String query,
                                                     String customerPhone, Language language) {
        List<ConversationTurn> history = customerPhone != null
                ? conversationContextService.getHistory(tenantId, customerPhone, MAX_HISTORY_TURNS)
                : List.of();
        List<KnowledgeEntry> kbContext = knowledgeBaseService.search(UUID.fromString(tenantId), query);
        String systemPrompt = promptAssembler.buildEmpathySystemPrompt(businessName, kbContext, language);
        String userMessage = promptAssembler.buildUserMessage(history, query);
        AiResponseResult result = aiChatPort.chat(systemPrompt, userMessage);
        log.debug("Empathy response generated for tenant={}, confidence={}", tenantId, result.confidence());
        return result;
    }

    protected AiResponseResult generateEmpathyResponseFallback(String tenantId, String businessName, String query,
                                                                String customerPhone, Language language,
                                                                Throwable cause) {
        return new AiResponseResult(fallbackProvider.getFallbackResponse(tenantId, cause), 0.0, true);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException("SHA-256 is unavailable", ex);
        }
    }
}
