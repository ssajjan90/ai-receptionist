package com.aireceptionist.tenant.service;

import com.aireceptionist.auth.security.AuthUtils;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.tenant.dto.TenantRequest;
import com.aireceptionist.tenant.dto.TenantResponse;
import com.aireceptionist.tenant.entity.Tenant;
import com.aireceptionist.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantService {

    private final TenantRepository tenantRepository;
    private final AuthUtils authUtils;

    // tenant-scoped: caller may only access own tenant data
    public List<TenantResponse> findAll() {
        if (!authUtils.isCurrentUserSuperAdmin()) {
            throw new AccessDeniedException("Only SUPER_ADMIN can access all tenants.");
        }
        return tenantRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    // tenant-scoped: caller may only access own tenant data
    public TenantResponse findById(Long id) {
        if (!authUtils.isCurrentUserSuperAdmin() && !id.equals(authUtils.getCurrentUserTenantId())) {
            throw new AccessDeniedException("Access denied for tenant id: " + id);
        }
        return toResponse(getTenantOrThrow(id));
    }

    @Transactional
    public TenantResponse create(TenantRequest request) {
        if (request.getEmail() != null && tenantRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("A tenant with this email already exists.");
        }
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .industry(request.getIndustry())
                .phone(request.getPhone())
                .email(request.getEmail())
                .address(request.getAddress())
                .workingHours(request.getWorkingHours())
                .defaultLanguage(request.getDefaultLanguage())
                .supportedLanguages(request.getSupportedLanguages())
                .build();
        return toResponse(tenantRepository.save(tenant));
    }

    @Transactional
    public TenantResponse update(Long id, TenantRequest request) {
        Tenant tenant = getTenantOrThrow(id);
        tenant.setName(request.getName());
        tenant.setIndustry(request.getIndustry());
        tenant.setPhone(request.getPhone());
        tenant.setEmail(request.getEmail());
        tenant.setAddress(request.getAddress());
        tenant.setWorkingHours(request.getWorkingHours());
        tenant.setDefaultLanguage(request.getDefaultLanguage());
        tenant.setSupportedLanguages(request.getSupportedLanguages());
        return toResponse(tenantRepository.save(tenant));
    }

    @Transactional
    public void delete(Long id) {
        getTenantOrThrow(id);
        tenantRepository.deleteById(id);
    }

    public Tenant getTenantOrThrow(Long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }

    private TenantResponse toResponse(Tenant t) {
        return TenantResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .industry(t.getIndustry())
                .phone(t.getPhone())
                .email(t.getEmail())
                .address(t.getAddress())
                .workingHours(t.getWorkingHours())
                .defaultLanguage(t.getDefaultLanguage())
                .supportedLanguages(t.getSupportedLanguages())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
