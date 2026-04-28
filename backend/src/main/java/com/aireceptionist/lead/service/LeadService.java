package com.aireceptionist.lead.service;

import com.aireceptionist.auth.security.AuthUtils;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.lead.dto.LeadRequest;
import com.aireceptionist.lead.dto.LeadResponse;
import com.aireceptionist.lead.dto.LeadStatusRequest;
import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.repository.LeadRepository;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeadService {

    private final LeadRepository leadRepository;
    private final TenantService tenantService;
    private final AuthUtils authUtils;

    // tenant-scoped: caller may only access own tenant data
    public LeadResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public List<LeadResponse> findByTenant(Long tenantId) {
        tenantService.getTenantOrThrow(tenantId);
        return leadRepository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    // tenant-scoped: caller may only access own tenant data
    public Long getTenantIdByLeadId(Long id) {
        return getOrThrow(id).getTenantId();
    }

    @Transactional
    // tenant-scoped: caller may only access own tenant data
    public LeadResponse create(LeadRequest request) {
        if (!authUtils.isCurrentUserSuperAdmin()) {
            request.setTenantId(authUtils.getCurrentUserTenantId());
        }
        tenantService.getTenantOrThrow(request.getTenantId());
        Lead lead = Lead.builder()
                .tenantId(request.getTenantId())
                .customerName(request.getCustomerName())
                .phone(request.getPhone())
                .email(request.getEmail())
                .requirement(request.getRequirement())
                .source(request.getSource())
                .build();
        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    public Lead createInternal(Long tenantId, String customerPhone, String requirement, Lead.LeadSource source) {
        Lead lead = Lead.builder()
                .tenantId(tenantId)
                .phone(customerPhone)
                .requirement(requirement)
                .source(source)
                .build();
        return leadRepository.save(lead);
    }

    @Transactional
    // tenant-scoped: caller may only access own tenant data
    public LeadResponse updateStatus(Long id, LeadStatusRequest request) {
        Lead lead = getOrThrow(id);
        lead.setStatus(request.getStatus());
        return toResponse(leadRepository.save(lead));
    }

    private Lead getOrThrow(Long id) {
        if (!authUtils.isCurrentUserSuperAdmin()) {
            return leadRepository.findByIdAndTenantId(id, authUtils.getCurrentUserTenantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        }
        return leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));
    }

    private LeadResponse toResponse(Lead l) {
        return LeadResponse.builder()
                .id(l.getId())
                .tenantId(l.getTenantId())
                .customerName(l.getCustomerName())
                .phone(l.getPhone())
                .email(l.getEmail())
                .requirement(l.getRequirement())
                .status(l.getStatus())
                .source(l.getSource())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }
}
