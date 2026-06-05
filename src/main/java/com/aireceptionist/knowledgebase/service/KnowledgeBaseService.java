package com.aireceptionist.knowledgebase.service;

import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.event.KnowledgeEntryAddedEvent;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final String WIZARD_SOURCE = "WIZARD";
    private static final String OCR_SOURCE = "OCR";
    private static final Set<String> STOP_WORDS = Set.of(
            "what", "is", "the", "a", "an", "how", "much", "price", "of", "are", "does", "do",
            "i", "me", "my", "we", "you", "it", "this", "that", "for", "in", "on", "at", "to"
    );
    private static final int MAX_SEARCH_RESULTS = 5;
    private static final String KB_CACHE_PREFIX = "tenant:";
    private static final String KB_CACHE_SUFFIX = ":kb:";

    private final KnowledgeEntryRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration kbCacheTtl;

    public KnowledgeBaseService(KnowledgeEntryRepository repository,
                                ApplicationEventPublisher eventPublisher,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                @Value("${app.cache.response-ttl-minutes:5}") int cacheTtlMinutes) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.kbCacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    public int bulkUpsertProducts(UUID tenantId, List<ProductKnowledgeEntry> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }

        Set<String> submittedNames = new HashSet<>();
        products.forEach(product -> submittedNames.add(product.productName()));
        repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.PRODUCT, WIZARD_SOURCE).stream()
                .filter(entry -> !submittedNames.contains(entry.getProductName()))
                .forEach(repository::delete);

        List<KnowledgeEntry> entries = new ArrayList<>();
        for (ProductKnowledgeEntry product : products) {
            KnowledgeEntry entry = repository
                    .findByTenantIdAndTypeAndSourceAndProductName(
                            tenantId,
                            EntryType.PRODUCT,
                            WIZARD_SOURCE,
                            product.productName()
                    )
                    .orElseGet(() -> KnowledgeEntry.onboardingProduct(tenantId, product.productName(), product.price()));
            entry.updateProduct(product.price());
            entries.add(entry);
        }
        repository.saveAll(entries);
        eventPublisher.publishEvent(new KnowledgeEntryAddedEvent(tenantId, entries.size()));
        return entries.size();
    }

    public int bulkUpsertOcrProducts(UUID tenantId, List<ProductKnowledgeEntry> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }

        List<KnowledgeEntry> entries = new ArrayList<>();
        for (ProductKnowledgeEntry product : products) {
            KnowledgeEntry entry = repository
                    .findByTenantIdAndTypeAndSourceAndProductName(
                            tenantId,
                            EntryType.PRODUCT,
                            OCR_SOURCE,
                            product.productName()
                    )
                    .orElseGet(() -> KnowledgeEntry.product(tenantId, product.productName(), product.price(), OCR_SOURCE));
            entry.updateProduct(product.price(), OCR_SOURCE);
            entries.add(entry);
        }
        repository.saveAll(entries);
        eventPublisher.publishEvent(new KnowledgeEntryAddedEvent(tenantId, entries.size()));
        return entries.size();
    }

    public int bulkUpsertFaqs(UUID tenantId, List<FaqKnowledgeEntry> faqs) {
        if (faqs == null || faqs.isEmpty()) {
            repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.FAQ, WIZARD_SOURCE)
                    .forEach(repository::delete);
            return 0;
        }

        Set<String> submittedQuestions = new HashSet<>();
        faqs.forEach(faq -> submittedQuestions.add(faq.question()));
        repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.FAQ, WIZARD_SOURCE).stream()
                .filter(entry -> !submittedQuestions.contains(entry.getQuestion()))
                .forEach(repository::delete);

        List<KnowledgeEntry> entries = new ArrayList<>();
        for (FaqKnowledgeEntry faq : faqs) {
            KnowledgeEntry entry = repository
                    .findByTenantIdAndTypeAndSourceAndQuestion(
                            tenantId,
                            EntryType.FAQ,
                            WIZARD_SOURCE,
                            faq.question()
                    )
                    .orElseGet(() -> KnowledgeEntry.onboardingFaq(tenantId, faq.question(), faq.answer()));
            entry.updateFaq(faq.answer());
            entries.add(entry);
        }
        repository.saveAll(entries);
        eventPublisher.publishEvent(new KnowledgeEntryAddedEvent(tenantId, entries.size()));
        return entries.size();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeEntry> findByTenantId(UUID tenantId) {
        return repository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeEntry> search(UUID tenantId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String cacheKey = KB_CACHE_PREFIX + tenantId + KB_CACHE_SUFFIX + sha256(query.toLowerCase().trim());
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<KnowledgeEntry>>() {});
            } catch (Exception ex) {
                log.debug("KB cache parse failed, re-querying: {}", ex.getMessage());
            }
        }

        List<String> tokens = Arrays.stream(query.toLowerCase().split("\\s+"))
                .map(String::trim)
                .filter(t -> t.length() > 1 && !STOP_WORDS.contains(t))
                .distinct()
                .toList();

        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<UUID, int[]> hitCount = new LinkedHashMap<>();
        Map<UUID, KnowledgeEntry> entryById = new LinkedHashMap<>();
        for (String token : tokens) {
            List<KnowledgeEntry> matches = repository.searchByKeyword(tenantId, "%" + token + "%");
            for (KnowledgeEntry entry : matches) {
                hitCount.computeIfAbsent(entry.getId(), id -> new int[]{0})[0]++;
                entryById.put(entry.getId(), entry);
            }
        }

        List<KnowledgeEntry> results = hitCount.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<UUID, int[]> e) -> e.getValue()[0]).reversed())
                .limit(MAX_SEARCH_RESULTS)
                .map(e -> entryById.get(e.getKey()))
                .toList();

        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(results), kbCacheTtl);
        } catch (Exception ex) {
            log.debug("Failed to cache KB search results: {}", ex.getMessage());
        }
        return results;
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
