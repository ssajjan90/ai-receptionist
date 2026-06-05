package com.aireceptionist.common.security;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractBearerToken(request);
        try {
            if (token != null) {
                authenticate(token);
            }
            filterChain.doFilter(request, response);
        } catch (JOSEException | ParseException ex) {
            writeUnauthorized(response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token) throws JOSEException, ParseException {
        JWTClaimsSet claims = tokenProvider.validateAndParseClaims(token);
        String tenantId = claims.getStringClaim("tenantId");
        String userId = claims.getStringClaim("userId");
        String role = claims.getStringClaim("role");
        String tier = claims.getStringClaim("tier");

        if (tenantId == null || userId == null || role == null || tier == null) {
            throw new JOSEException("JWT missing required claims: tenantId, userId, role, tier");
        }

        TenantContext.setCurrentTenant(tenantId);
        SecurityContextHolder.getContext()
                .setAuthentication(new TenantAwareAuthentication(tenantId, userId, role, tier));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var writer = response.getWriter();
        writer.write("""
                {"success":false,"errorCode":"UNAUTHORIZED","message":"Invalid or expired JWT token","timestamp":"%s"}
                """.formatted(Instant.now()));
        writer.flush();
    }
}
