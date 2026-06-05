package com.aireceptionist.tenant.application;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.knowledgebase.service.FaqKnowledgeEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.knowledgebase.service.ProductKnowledgeEntry;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingResponse;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import com.aireceptionist.tenant.event.TenantOnboardedEvent;
import com.aireceptionist.tenant.port.in.CompleteTenantOnboardingUseCase;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TenantOnboardingService implements CompleteTenantOnboardingUseCase {

    private final TenantRegistrationRepository tenantRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ApplicationEventPublisher eventPublisher;

    public TenantOnboardingService(TenantRegistrationRepository tenantRepository,
                                   KnowledgeBaseService knowledgeBaseService,
                                   ApplicationEventPublisher eventPublisher) {
        this.tenantRepository = tenantRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public OnboardingResponse completeOnboarding(UUID tenantId, OnboardingRequest request) {
        BusinessTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found."));

        if (tenant.getStatus() != TenantStatus.ACTIVE && tenant.getStatus() != TenantStatus.ONBOARDING_COMPLETE) {
            throw new AuthorizationException("ONBOARDING_NOT_ALLOWED", "Tenant onboarding is allowed only for active tenants.");
        }

        int productsCreated = knowledgeBaseService.bulkUpsertProducts(tenantId, request.topProducts().stream()
                .map(product -> new ProductKnowledgeEntry(product.productName(), product.price()))
                .toList());
        int faqsCreated = knowledgeBaseService.bulkUpsertFaqs(tenantId, toFaqKnowledgeEntries(request));

        tenant.completeOnboarding(
                request.shopName(),
                request.location(),
                request.businessHours(),
                request.resolvedPreferredLanguage()
        );
        BusinessTenant savedTenant = tenantRepository.save(tenant);
        eventPublisher.publishEvent(new TenantOnboardedEvent(savedTenant.getId(), savedTenant.getBusinessName()));

        return new OnboardingResponse(
                savedTenant.getId(),
                savedTenant.getStatus(),
                productsCreated + faqsCreated,
                "Onboarding completed"
        );
    }

    private List<FaqKnowledgeEntry> toFaqKnowledgeEntries(OnboardingRequest request) {
        if (request.commonFaqs() == null) {
            return List.of();
        }
        return request.commonFaqs().stream()
                .map(faq -> new FaqKnowledgeEntry(faq.question(), faq.answer()))
                .toList();
    }
}
