package com.aireceptionist.common.ratelimit;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.multitenancy.TenantIdentifierResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RateLimitFilter extends OncePerRequestFilter {

    private final TenantRateLimitService rateLimitService;

    public RateLimitFilter(TenantRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = TenantContext.getCurrentTenant();

        if (tenantId == null || TenantIdentifierResolver.DEFAULT_TENANT.equals(tenantId)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!rateLimitService.tryConsume(tenantId)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            response.getWriter().flush();
            return;
        }

        filterChain.doFilter(request, response);
    }
}
