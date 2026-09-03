package com.aireceptionist.admin.api;

import com.aireceptionist.admin.dto.AdminDashboardResponse;
import com.aireceptionist.admin.dto.AuditLogEntryResponse;
import com.aireceptionist.admin.dto.AuditLogPageResponse;
import com.aireceptionist.admin.dto.BroadcastRequest;
import com.aireceptionist.admin.dto.BroadcastResult;
import com.aireceptionist.admin.dto.ConversationLogResponse;
import com.aireceptionist.admin.dto.NotifyRequest;
import com.aireceptionist.admin.dto.TenantDetailResponse;
import com.aireceptionist.admin.service.AdminService;
import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.RateLimitExceededException;
import com.aireceptionist.common.ratelimit.TenantRateLimitService;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.tenant.port.in.TenantDataExport;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController extends VersionedRestController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminService adminService;
    private final TenantRateLimitService rateLimitService;

    public AdminController(AdminService adminService, TenantRateLimitService rateLimitService) {
        this.adminService = adminService;
        this.rateLimitService = rateLimitService;
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

    /**
     * Story 5.5 (AC2). Not {@code @Transactional(readOnly = true)} — same reason as
     * {@link #getConversations}: this writes an {@code ADMIN_DATA_EXPORT} audit entry as a side
     * effect, which a read-only transaction would reject.
     */
    @GetMapping("/tenants/{tenantId}/export")
    public ApiResponse<TenantDataExport> exportTenantData(@PathVariable UUID tenantId, Authentication authentication) {
        return ApiResponse.ok(adminService.exportTenantData(currentAdminId(authentication), tenantId));
    }

    /**
     * Story 5.3 (AC1-AC4). No write endpoints exist for conversation data itself (Dev Notes) —
     * this call does write an {@code ADMIN_CONVERSATION_VIEW} audit entry (AC3) as a side effect,
     * so, unlike {@link #getTenantDetail}, it's deliberately NOT {@code @Transactional(readOnly = true)}:
     * that would reject the audit INSERT with a Postgres read-only-transaction error.
     */
    @GetMapping("/tenants/{tenantId}/conversations")
    public ApiResponse<Page<ConversationLogResponse>> getConversations(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50) Pageable pageable,
            Authentication authentication) {
        Pageable boundedPageable = pageable.getPageSize() > MAX_PAGE_SIZE
                ? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
        return ApiResponse.ok(adminService.findConversations(
                currentAdminId(authentication), tenantId, from, to, boundedPageable));
    }

    /**
     * Story 5.6 (AC1-AC4). {@code tenantId} is a required query param (not a path variable, unlike
     * {@link #getConversations}) — matches the story's literal URL shape. Not {@code
     * @Transactional(readOnly = true)} — same reason as {@link #getConversations}: this writes an
     * {@code ADMIN_AUDIT_VIEW} audit entry as a side effect.
     *
     * <p>Code review, 2026-09-03: uses keyset/seek pagination ({@code cursorOccurredAt}/
     * {@code cursorId}, from the previous response's {@code nextCursorOccurredAt}/
     * {@code nextCursorId}) instead of a page number — see {@link AdminService#queryAuditLog}'s
     * javadoc for why {@code OFFSET} paging is unsafe for this specific endpoint.
     */
    @GetMapping("/audit-log")
    public ApiResponse<AuditLogPageResponse> queryAuditLog(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorOccurredAt,
            @RequestParam(required = false) UUID cursorId,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication authentication) {
        int boundedSize = Math.min(size, MAX_PAGE_SIZE);
        return ApiResponse.ok(adminService.queryAuditLog(
                currentAdminId(authentication), tenantId, from, to, eventType, cursorOccurredAt, cursorId, boundedSize));
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public ApiResponse<TenantDetailResponse> suspendTenant(@PathVariable UUID tenantId, Authentication authentication) {
        adminService.suspendTenant(currentAdminId(authentication), tenantId);
        return ApiResponse.ok(adminService.findTenantDetail(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/reactivate")
    public ApiResponse<TenantDetailResponse> reactivateTenant(@PathVariable UUID tenantId, Authentication authentication) {
        adminService.reactivateTenant(currentAdminId(authentication), tenantId);
        return ApiResponse.ok(adminService.findTenantDetail(tenantId));
    }

    @DeleteMapping("/tenants/{tenantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminateTenant(@PathVariable UUID tenantId, Authentication authentication) {
        adminService.terminateTenant(currentAdminId(authentication), tenantId);
    }

    /** Story 5.4 (AC1, AC2, AC3). */
    @PostMapping("/tenants/{tenantId}/notify")
    public ApiResponse<Void> notifyTenant(@PathVariable UUID tenantId, @Valid @RequestBody NotifyRequest request,
                                          Authentication authentication) {
        UUID adminId = currentAdminId(authentication);
        if (!rateLimitService.tryConsumeAdminNotification(adminId)) {
            throw new RateLimitExceededException("Admin notification rate limit exceeded (10 per minute).");
        }
        adminService.notifyTenant(adminId, tenantId, request.message());
        return ApiResponse.ok(null);
    }

    /**
     * Story 5.4 (AC4). Code review, 2026-09-01: originally left unthrottled on the reading that
     * AC3 names the single-tenant {@code /notify} endpoint specifically — but that let one call
     * with an unbounded tenant list trigger unlimited WhatsApp sends, undermining AC3's actual
     * abuse-prevention intent. Fixed: {@code BroadcastRequest.tenantIds} is now capped
     * ({@code @Size(max = 100)}), and one call consumes exactly one token from the same per-admin
     * bucket {@code /notify} uses — bounding both a single giant list and rapid repeated
     * broadcasts, without cutting broadcast down to {@code /notify}'s per-tenant granularity.
     */
    @PostMapping("/broadcast")
    public ApiResponse<BroadcastResult> broadcast(@Valid @RequestBody BroadcastRequest request,
                                                  Authentication authentication) {
        UUID adminId = currentAdminId(authentication);
        if (!rateLimitService.tryConsumeAdminNotification(adminId)) {
            throw new RateLimitExceededException("Admin notification rate limit exceeded (10 per minute).");
        }
        BroadcastResult result = adminService.broadcast(adminId, request.tenantIds(), request.message());
        return ApiResponse.ok(result);
    }

    private UUID currentAdminId(Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication auth) || auth.getUserId() == null) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated admin context is required.");
        }
        try {
            return UUID.fromString(auth.getUserId());
        } catch (IllegalArgumentException ex) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated admin context is required.");
        }
    }
}
