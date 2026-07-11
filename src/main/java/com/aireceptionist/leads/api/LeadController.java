package com.aireceptionist.leads.api;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.dto.LeadMapper;
import com.aireceptionist.leads.dto.LeadResponse;
import com.aireceptionist.leads.dto.UpdateLeadStatusRequest;
import com.aireceptionist.leads.service.LeadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/tenants/{tenantId}/leads")
public class LeadController extends VersionedRestController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LeadService leadService;
    private final LeadMapper leadMapper;

    public LeadController(LeadService leadService, LeadMapper leadMapper) {
        this.leadService = leadService;
        this.leadMapper = leadMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Page<LeadResponse>> listLeads(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) LeadStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        Pageable boundedPageable = pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
        Page<LeadResponse> page = leadService.findLeads(tenantId, status, boundedPageable)
                .map(leadMapper::toResponse);
        return ApiResponse.ok(page);
    }

    @PatchMapping("/{leadId}")
    public ApiResponse<LeadResponse> updateStatus(
            @PathVariable UUID tenantId,
            @PathVariable UUID leadId,
            @Valid @RequestBody UpdateLeadStatusRequest request,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        return ApiResponse.ok(leadMapper.toResponse(
                leadService.updateStatus(tenantId, leadId, request.status())));
    }

    @GetMapping("/export")
    @Transactional(readOnly = true)
    public ApiResponse<List<LeadResponse>> exportLeads(
            @PathVariable UUID tenantId,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        List<LeadResponse> leads = leadService.exportLeads(tenantId).stream()
                .map(leadMapper::toResponse)
                .toList();
        return ApiResponse.ok(leads);
    }

    @PostMapping("/{leadId}/erase")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eraseLead(
            @PathVariable UUID tenantId,
            @PathVariable UUID leadId,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        leadService.eraseLead(tenantId, leadId);
    }

    @PostMapping("/erase-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eraseAllLeads(
            @PathVariable UUID tenantId,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        leadService.eraseAllLeads(tenantId);
    }

    private void validateTenantOwnership(UUID tenantId, Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication tenantAuthentication)) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated tenant context is required.");
        }

        UUID authenticatedTenantId;
        try {
            authenticatedTenantId = UUID.fromString(tenantAuthentication.getTenantId());
        } catch (IllegalArgumentException ex) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }

        if (!tenantId.equals(authenticatedTenantId)) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }
    }
}
