package com.aireceptionist.knowledge.service;

import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.knowledge.dto.KnowledgeRequest;
import com.aireceptionist.knowledge.dto.KnowledgeResponse;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import com.aireceptionist.knowledge.repository.KnowledgeBaseRepository;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KnowledgeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TenantService tenantService;

    public List<KnowledgeResponse> findByTenant(Long tenantId) {
        tenantService.getTenantOrThrow(tenantId);
        return knowledgeBaseRepository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<KnowledgeBase> findActiveByTenant(Long tenantId) {
        return knowledgeBaseRepository.findByTenantIdAndActiveTrue(tenantId);
    }

    @Transactional
    public KnowledgeResponse create(Long tenantId, KnowledgeRequest request) {
        tenantService.getTenantOrThrow(tenantId);
        KnowledgeBase kb = KnowledgeBase.builder()
                .tenantId(tenantId)
                .type(request.getType())
                .question(request.getQuestion())
                .answer(request.getAnswer())
                .active(request.isActive())
                .build();
        return toResponse(knowledgeBaseRepository.save(kb));
    }

    @Transactional
    public KnowledgeResponse update(Long id, KnowledgeRequest request) {
        KnowledgeBase kb = getOrThrow(id);
        kb.setType(request.getType());
        kb.setQuestion(request.getQuestion());
        kb.setAnswer(request.getAnswer());
        kb.setActive(request.isActive());
        return toResponse(knowledgeBaseRepository.save(kb));
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        knowledgeBaseRepository.deleteById(id);
    }

    private KnowledgeBase getOrThrow(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeBase", id));
    }

    private KnowledgeResponse toResponse(KnowledgeBase kb) {
        return KnowledgeResponse.builder()
                .id(kb.getId())
                .tenantId(kb.getTenantId())
                .type(kb.getType())
                .question(kb.getQuestion())
                .answer(kb.getAnswer())
                .active(kb.isActive())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }
}
