package com.aireceptionist.admin.web;

import com.aireceptionist.admin.domain.AdminAccessLogEntry;
import com.aireceptionist.admin.repository.AdminAccessLogRepository;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one ADMIN_ACCESS entry per successful admin API call (AC5). Runs in postHandle so
 * requests rejected by {@code @PreAuthorize} (non-admin JWTs, AC4) are not recorded as access.
 */
public class AdminAccessAuditInterceptor implements HandlerInterceptor {

    private final AdminAccessLogRepository repository;

    public AdminAccessAuditInterceptor(AdminAccessLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler, org.springframework.web.servlet.ModelAndView modelAndView) {
        if (!(handler instanceof HandlerMethod)) {
            return;
        }

        UUID adminUserId = currentAdminUserId();
        if (adminUserId == null) {
            return;
        }

        repository.save(new AdminAccessLogEntry(
                UUID.randomUUID(),
                adminUserId,
                extractTargetTenantId(request),
                "ADMIN_ACCESS",
                request.getMethod() + " " + request.getRequestURI(),
                Instant.now()));
    }

    private UUID currentAdminUserId() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof TenantAwareAuthentication auth)) {
            return null;
        }
        try {
            return UUID.fromString(auth.getUserId());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private UUID extractTargetTenantId(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return null;
        }
        Object tenantId = ((Map<String, String>) variables).get("tenantId");
        if (tenantId == null) {
            return null;
        }
        try {
            return UUID.fromString(tenantId.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
