package com.aireceptionist.admin.web;

import com.aireceptionist.admin.domain.AdminAccessLogEntry;
import com.aireceptionist.admin.repository.AdminAccessLogRepository;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one ADMIN_ACCESS entry per admin API call, success or failure (AC5) — runs in
 * {@code afterCompletion}, which fires regardless of whether the handler threw, so an
 * authorized-but-failed call (e.g. a 404 tenant lookup) is still audited. The one deliberate
 * exclusion is a 403 from {@code @PreAuthorize} (non-admin JWTs, AC4): that's a rejected access
 * attempt, not a call an authorized admin made, so it isn't recorded.
 *
 * <p>Exclusion is done via the response status, not the {@code ex} parameter: Spring's
 * {@code DispatcherServlet} passes {@code null} to {@code afterCompletion} whenever a
 * {@code HandlerExceptionResolver} (here, {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(AccessDeniedException.class)}) successfully resolves the exception
 * into a response — the original exception type is not reliably available here.</p>
 */
public class AdminAccessAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessAuditInterceptor.class);

    private final AdminAccessLogRepository repository;

    public AdminAccessAuditInterceptor(AdminAccessLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod)) {
            return;
        }
        if (response.getStatus() == HttpServletResponse.SC_FORBIDDEN) {
            return;
        }

        UUID adminUserId = currentAdminUserId();
        if (adminUserId == null) {
            log.warn("Admin access to {} {} not audited — no parseable admin user id on the authentication",
                    request.getMethod(), request.getRequestURI());
            return;
        }

        try {
            repository.save(new AdminAccessLogEntry(
                    UUID.randomUUID(),
                    adminUserId,
                    extractTargetTenantId(request),
                    "ADMIN_ACCESS",
                    request.getMethod() + " " + request.getRequestURI(),
                    Instant.now()));
        } catch (Exception saveEx) {
            // Never let an audit-log write failure turn an otherwise-successful (or already
            // failed-for-its-own-reasons) admin response into a 500.
            log.warn("Failed to write admin access log entry for {} {} by admin {}",
                    request.getMethod(), request.getRequestURI(), adminUserId, saveEx);
        }
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
