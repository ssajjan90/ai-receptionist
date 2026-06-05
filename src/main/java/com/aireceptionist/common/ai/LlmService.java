package com.aireceptionist.common.ai;

import com.aireceptionist.ai.dto.ConversationTurn;
import com.aireceptionist.ai.service.ConversationContextService;
import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.PromptAssembler;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
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
    private final Duration responseCacheTtl;

    public LlmService(AiChatPort aiChatPort,
                      KnowledgeBaseService knowledgeBaseService,
                      ConversationContextService conversationContextService,
                      PromptAssembler promptAssembler,
                      FallbackMessageProvider fallbackProvider,
                      StringRedisTemplate redisTemplate,
                      @Value("${app.cache.response-ttl-minutes:5}") int cacheTtlMinutes) {
        this.aiChatPort = aiChatPort;
        this.knowledgeBaseService = knowledgeBaseService;
        this.conversationContextService = conversationContextService;
        this.promptAssembler = promptAssembler;
        this.fallbackProvider = fallbackProvider;
        this.redisTemplate = redisTemplate;
        this.responseCacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    @AiResponse(eventType = "QUERY_RESPONSE")
    @CircuitBreaker(name = "llmService", fallbackMethod = "generateResponseFallback")
    public AiResponseResult generateResponse(String tenantId, String query,
                                              String customerPhone, Language language) {
        String cacheKey = CACHE_PREFIX + tenantId + CACHE_QUERY_INFIX + sha256(query.toLowerCase().trim());
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("LLM cache hit for tenant={}", tenantId);
            return new AiResponseResult(cached, 1.0);
        }

        List<ConversationTurn> history = customerPhone != null
                ? conversationContextService.getHistory(tenantId, customerPhone, MAX_HISTORY_TURNS)
                : List.of();

        List<KnowledgeEntry> kbContext = knowledgeBaseService.search(UUID.fromString(tenantId), query);

        String systemPrompt = promptAssembler.buildSystemPrompt(tenantId, kbContext, language);
        String userMessage = promptAssembler.buildUserMessage(history, query);

        AiResponseResult result = aiChatPort.chat(systemPrompt, userMessage);
        log.debug("LLM response generated for tenant={}, confidence={}", tenantId, result.confidence());

        redisTemplate.opsForValue().set(cacheKey, result.response(), responseCacheTtl);
        return result;
    }

    protected AiResponseResult generateResponseFallback(String tenantId, String query,
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
            return Integer.toHexString(input.hashCode());
        }
    }
}
