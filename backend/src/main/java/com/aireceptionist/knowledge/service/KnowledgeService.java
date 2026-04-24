package com.aireceptionist.knowledge.service;

import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.knowledge.dto.KnowledgeBaseRequest;
import com.aireceptionist.knowledge.dto.KnowledgeBaseResponse;
import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.entity.KnowledgeIntent;
import com.aireceptionist.knowledge.repository.KnowledgeBaseRepository;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KnowledgeService {

    private static final String ENGLISH = "English";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TenantService tenantService;

    public List<KnowledgeBaseResponse> findByTenant(Long tenantId) {
        tenantService.getTenantOrThrow(tenantId);
        return knowledgeBaseRepository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<KnowledgeBaseResponse> findDefaultByIndustry(IndustryType industry) {
        return knowledgeBaseRepository.findByTenantIdIsNullAndIndustryAndActiveTrue(industry).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<KnowledgeBase> findBestMatches(Long tenantId,
                                               IndustryType industry,
                                               String customerMessage,
                                               String language) {
        String requestedLanguage = normalizeLanguage(language);

        Map<Long, KnowledgeBase> candidates = new LinkedHashMap<>();
        addAll(candidates, knowledgeBaseRepository.findByTenantIdAndIndustryAndLanguageIgnoreCaseAndActiveTrue(
                tenantId, industry, requestedLanguage));

        if (!isEnglish(requestedLanguage)) {
            addAll(candidates, knowledgeBaseRepository.findByTenantIdAndIndustryAndLanguageIgnoreCaseAndActiveTrue(
                    tenantId, industry, ENGLISH));
        }

        addAll(candidates, knowledgeBaseRepository.findByTenantIdIsNullAndIndustryAndLanguageIgnoreCaseAndActiveTrue(
                industry, requestedLanguage));

        if (!isEnglish(requestedLanguage)) {
            addAll(candidates, knowledgeBaseRepository.findByTenantIdIsNullAndIndustryAndLanguageIgnoreCaseAndActiveTrue(
                    industry, ENGLISH));
        }

        return candidates.values().stream()
                .filter(entry -> matches(entry, customerMessage))
                .sorted(Comparator.comparing(KnowledgeBase::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed())
                .limit(3)
                .toList();
    }

    @Transactional
    public KnowledgeBaseResponse create(KnowledgeBaseRequest request) {
        if (request.getTenantId() != null) {
            tenantService.getTenantOrThrow(request.getTenantId());
        }

        KnowledgeBase kb = fromRequest(request, null);
        return toResponse(knowledgeBaseRepository.save(kb));
    }

    @Transactional
    public KnowledgeBaseResponse createForTenant(Long tenantId, KnowledgeBaseRequest request) {
        tenantService.getTenantOrThrow(tenantId);
        request.setTenantId(tenantId);
        return create(request);
    }

    @Transactional
    public KnowledgeBaseResponse update(Long id, KnowledgeBaseRequest request) {
        KnowledgeBase existing = getOrThrow(id);
        if (request.getTenantId() != null) {
            tenantService.getTenantOrThrow(request.getTenantId());
        }

        KnowledgeBase updated = fromRequest(request, existing);
        return toResponse(knowledgeBaseRepository.save(updated));
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        knowledgeBaseRepository.deleteById(id);
    }

    @Transactional
    public int seedDefaultKnowledge() {
        List<KnowledgeBase> defaults = buildDefaults();
        int inserted = 0;

        for (KnowledgeBase entry : defaults) {
            boolean exists = knowledgeBaseRepository.findByTenantIdIsNullAndIndustryAndLanguageIgnoreCaseAndActiveTrue(
                            entry.getIndustry(), entry.getLanguage())
                    .stream()
                    .anyMatch(existing -> existing.getQuestion().equalsIgnoreCase(entry.getQuestion())
                            && existing.getIntent() == entry.getIntent());

            if (!exists) {
                knowledgeBaseRepository.save(entry);
                inserted++;
            }
        }

        return inserted;
    }

    private KnowledgeBase getOrThrow(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeBase", id));
    }

    private KnowledgeBase fromRequest(KnowledgeBaseRequest request, KnowledgeBase target) {
        KnowledgeBase kb = target == null ? new KnowledgeBase() : target;

        kb.setTenantId(request.getTenantId());
        kb.setIndustry(request.getIndustry());
        kb.setCategory(request.getCategory());
        kb.setIntent(request.getIntent());
        kb.setQuestion(request.getQuestion());
        kb.setAnswer(request.getAnswer());
        kb.setLanguage(normalizeLanguage(request.getLanguage()));
        kb.setAltQuestions(joinList(request.getAltQuestions()));
        kb.setKeywords(joinList(request.getKeywords()));
        kb.setPriority(request.getPriority() == null ? 1 : request.getPriority());
        kb.setActive(request.isActive());

        return kb;
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return KnowledgeBaseResponse.builder()
                .id(kb.getId())
                .tenantId(kb.getTenantId())
                .industry(kb.getIndustry())
                .category(kb.getCategory())
                .intent(kb.getIntent())
                .question(kb.getQuestion())
                .answer(kb.getAnswer())
                .language(kb.getLanguage())
                .altQuestions(splitList(kb.getAltQuestions()))
                .keywords(splitList(kb.getKeywords()))
                .priority(kb.getPriority())
                .active(kb.isActive())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    private void addAll(Map<Long, KnowledgeBase> store, List<KnowledgeBase> entries) {
        entries.forEach(entry -> store.putIfAbsent(entry.getId(), entry));
    }

    private boolean matches(KnowledgeBase entry, String message) {
        String normalizedMessage = normalize(message);
        if (normalizedMessage.isBlank()) {
            return false;
        }

        List<String> allPatterns = new ArrayList<>();
        allPatterns.add(entry.getQuestion());
        allPatterns.addAll(splitList(entry.getAltQuestions()));
        allPatterns.addAll(splitList(entry.getKeywords()));

        return allPatterns.stream().filter(Objects::nonNull)
                .map(this::normalize)
                .anyMatch(pattern -> !pattern.isBlank() && (normalizedMessage.contains(pattern)
                        || pattern.contains(normalizedMessage)
                        || hasTokenOverlap(normalizedMessage, pattern)));
    }

    private boolean hasTokenOverlap(String left, String right) {
        Set<String> leftTokens = Arrays.stream(left.split("\\s+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
        Set<String> rightTokens = Arrays.stream(right.split("\\s+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
        return leftTokens.stream().anyMatch(rightTokens::contains);
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return ENGLISH;
        }
        String trimmed = language.trim();
        return trimmed.equalsIgnoreCase("en") ? ENGLISH : trimmed;
    }

    private boolean isEnglish(String language) {
        return language != null && (language.equalsIgnoreCase(ENGLISH) || language.equalsIgnoreCase("en"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("|"));
    }

    private List<String> splitList(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<KnowledgeBase> buildDefaults() {
        return List.of(
                defaultEntry(IndustryType.CLINIC, KnowledgeIntent.HOURS, "Hours", "What are your working hours?",
                        "Our clinic hours vary by service. Please share your preferred date and time, and we will confirm availability.",
                        "English", List.of("When are you open?"), List.of("hours", "timings"), 10),
                defaultEntry(IndustryType.CLINIC, KnowledgeIntent.PRICE, "Pricing", "How much does treatment cost?",
                        "Pricing depends on the treatment type. Please share the service you need and your phone number; our team will contact you with details.",
                        "English", List.of("What is the consultation fee?"), List.of("price", "cost", "fee"), 9),
                defaultEntry(IndustryType.CLINIC, KnowledgeIntent.HOURS, "ಸಮಯ", "ನಿಮ್ಮ ಕೆಲಸದ ಸಮಯ ಏನು?",
                        "ನಮ್ಮ ಕ್ಲಿನಿಕ್ ಸಮಯ ಸೇವೆಯ ಪ್ರಕಾರ ಬದಲಾಗಬಹುದು. ದಯವಿಟ್ಟು ನಿಮಗೆ ಬೇಕಾದ ದಿನ ಮತ್ತು ಸಮಯ ಹಂಚಿಕೊಳ್ಳಿ; ನಾವು ದೃಢೀಕರಿಸುತ್ತೇವೆ.",
                        "Kannada", List.of("ನೀವು ಯಾವಾಗ ತೆರೆಯಿರುತ್ತೀರಿ?"), List.of("ಸಮಯ", "ಟೈಮಿಂಗ್"), 10),

                defaultEntry(IndustryType.HOTEL, KnowledgeIntent.BOOKING, "Booking", "Do you have rooms available?",
                        "Availability changes by date and room type. Please share check-in/check-out dates and your phone number so our team can assist you.",
                        "English", List.of("Can I book a room?"), List.of("availability", "rooms", "booking"), 10),
                defaultEntry(IndustryType.HOTEL, KnowledgeIntent.PRICE, "Tariff", "What is the room price?",
                        "Room rates depend on room category and dates. Please share your travel dates and preferences, and we will get back with options.",
                        "English", List.of("How much per night?"), List.of("price", "tariff"), 9),
                defaultEntry(IndustryType.HOTEL, KnowledgeIntent.BOOKING, "ಬುಕಿಂಗ್", "ಕೊಠಡಿ ಲಭ್ಯವಿದೆಯೆ?",
                        "ದಿನಾಂಕ ಮತ್ತು ಕೊಠಡಿ ಪ್ರಕಾರದ ಮೇಲೆ ಲಭ್ಯತೆ ಬದಲಾಗುತ್ತದೆ. ದಯವಿಟ್ಟು check-in/check-out ದಿನಾಂಕಗಳನ್ನು ಹಾಗೂ ನಿಮ್ಮ ಫೋನ್ ಸಂಖ್ಯೆ ಹಂಚಿಕೊಳ್ಳಿ.",
                        "Kannada", List.of("ನಾನು ರೂಂ ಬುಕ್ ಮಾಡಬಹುದೇ?"), List.of("ಲಭ್ಯತೆ", "ರೂಂ", "ಬುಕಿಂಗ್"), 10),

                defaultEntry(IndustryType.SALON, KnowledgeIntent.SERVICES, "Services", "What services do you offer?",
                        "We offer multiple salon services. Please tell us the service you are looking for and preferred time, and our team will assist you.",
                        "English", List.of("Do you do hair spa?"), List.of("services", "hair", "facial"), 10),
                defaultEntry(IndustryType.SALON, KnowledgeIntent.PRICE, "Pricing", "What are your service charges?",
                        "Service charges vary by stylist and package. Please share the service details and phone number for an exact quote.",
                        "English", List.of("How much for haircut?"), List.of("price", "charge"), 9),
                defaultEntry(IndustryType.SALON, KnowledgeIntent.SERVICES, "ಸೇವೆಗಳು", "ನೀವು ಯಾವ ಸೇವೆಗಳು ನೀಡುತ್ತೀರಿ?",
                        "ನಾವು ಹಲವಾರು ಸಲೂನ್ ಸೇವೆಗಳು ನೀಡುತ್ತೇವೆ. ದಯವಿಟ್ಟು ಬೇಕಾದ ಸೇವೆ ಮತ್ತು ಸಮಯ ತಿಳಿಸಿ; ನಮ್ಮ ತಂಡ ಸಂಪರ್ಕಿಸುತ್ತದೆ.",
                        "Kannada", List.of("ಹೇರ್ ಸ್ಪಾ ಇದೆಯೆ?"), List.of("ಸೇವೆಗಳು", "ಹೇರ್", "ಫೇಷಿಯಲ್"), 10),

                defaultEntry(IndustryType.MOBILE_SHOP, KnowledgeIntent.AVAILABILITY, "Availability", "Is this phone model available?",
                        "Stock changes quickly. Please share the model name, variant, and your phone number so we can confirm availability.",
                        "English", List.of("Do you have iPhone in stock?"), List.of("availability", "stock", "model"), 10),
                defaultEntry(IndustryType.MOBILE_SHOP, KnowledgeIntent.PRICE, "Price", "What is the mobile price?",
                        "Pricing varies by model and offers. Please share the exact model and your contact number for the latest quote.",
                        "English", List.of("Best price for Samsung?"), List.of("price", "offer"), 9),
                defaultEntry(IndustryType.MOBILE_SHOP, KnowledgeIntent.AVAILABILITY, "ಲಭ್ಯತೆ", "ಈ ಫೋನ್ ಮಾದರಿ ಲಭ್ಯವಿದೆಯೆ?",
                        "ಸ್ಟಾಕ್ ತ್ವರಿತವಾಗಿ ಬದಲಾಗುತ್ತದೆ. ದಯವಿಟ್ಟು ಮಾದರಿ ಹೆಸರು, ವರ್ಗ, ಮತ್ತು ನಿಮ್ಮ ಫೋನ್ ಸಂಖ್ಯೆ ಹಂಚಿಕೊಳ್ಳಿ; ನಾವು ದೃಢೀಕರಿಸುತ್ತೇವೆ.",
                        "Kannada", List.of("iPhone ಸ್ಟಾಕ್ ಇದೆಯೆ?"), List.of("ಲಭ್ಯತೆ", "ಸ್ಟಾಕ್", "ಮಾದರಿ"), 10)
        );
    }

    private KnowledgeBase defaultEntry(IndustryType industry,
                                       KnowledgeIntent intent,
                                       String category,
                                       String question,
                                       String answer,
                                       String language,
                                       List<String> altQuestions,
                                       List<String> keywords,
                                       int priority) {
        return KnowledgeBase.builder()
                .tenantId(null)
                .industry(industry)
                .category(category)
                .intent(intent)
                .question(question)
                .answer(answer)
                .language(language)
                .altQuestions(joinList(altQuestions))
                .keywords(joinList(keywords))
                .priority(priority)
                .active(true)
                .build();
    }
}
