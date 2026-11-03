package com.aireceptionist.voice.api;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.tenant.port.in.ResolveTenantByBusinessPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantVoiceRoute;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Story 6.2: sets {@link TenantContext} for Exotel webhooks <em>before</em> Spring MVC's
 * open-in-view interceptor opens the request's EntityManager. Hibernate resolves the tenant (and
 * so Postgres' {@code app.current_tenant} for RLS) when that EntityManager first gets a
 * connection; setting the tenant later inside the controller is too late and every
 * {@code voice_calls} write is rejected by RLS. This is the webhook equivalent of
 * {@code JwtAuthenticationFilter} setting the tenant from the JWT for authenticated APIs.
 *
 * <p>Tenant is resolved from Exotel's {@code To} (the business number). Only context is set here
 * — signature verification and all processing stay in {@link VoiceWebhookController}. The
 * resolved route is passed on as a request attribute to avoid a second lookup.
 */
@Component
class VoiceTenantContextFilter extends OncePerRequestFilter {

    static final String ROUTE_ATTRIBUTE = VoiceTenantContextFilter.class.getName() + ".route";

    private final ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase;

    VoiceTenantContextFilter(ResolveTenantByBusinessPhoneUseCase resolveTenantByBusinessPhoneUseCase) {
        this.resolveTenantByBusinessPhoneUseCase = resolveTenantByBusinessPhoneUseCase;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/webhooks/voice");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String to = request.getParameter("To");
        Optional<ResolvedTenantVoiceRoute> route = to == null || to.isBlank()
                ? Optional.empty()
                : resolveTenantByBusinessPhoneUseCase.resolveByBusinessPhone(to);
        if (route.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String previous = TenantContext.getCurrentTenant();
        request.setAttribute(ROUTE_ATTRIBUTE, route.get());
        TenantContext.setCurrentTenant(route.get().tenantId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
