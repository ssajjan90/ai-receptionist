package com.aireceptionist.admin.api;

import com.aireceptionist.admin.dto.AdminDashboardResponse;
import com.aireceptionist.admin.dto.TenantDetailResponse;
import com.aireceptionist.admin.service.AdminService;
import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController extends VersionedRestController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/tenants")
    @Transactional(readOnly = true)
    public ApiResponse<Page<AdminDashboardResponse>> listTenants(
            @PageableDefault(size = 20) Pageable pageable) {
        Pageable boundedPageable = pageable.getPageSize() > MAX_PAGE_SIZE
                ? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
        return ApiResponse.ok(adminService.findAllTenants(boundedPageable));
    }

    @GetMapping("/tenants/{tenantId}")
    @Transactional(readOnly = true)
    public ApiResponse<TenantDetailResponse> getTenantDetail(@PathVariable UUID tenantId) {
        return ApiResponse.ok(adminService.findTenantDetail(tenantId));
    }
}
