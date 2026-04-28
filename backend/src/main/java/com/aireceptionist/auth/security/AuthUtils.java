package com.aireceptionist.auth.security;

import com.aireceptionist.auth.entity.User.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthUtils {

    private final JwtTokenProvider jwtTokenProvider;

    public AuthUtils(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public Long getCurrentUserId() {
        return jwtTokenProvider.extractUserId(getCurrentToken());
    }

    public Long getCurrentUserTenantId() {
        return jwtTokenProvider.extractTenantId(getCurrentToken());
    }

    public Role getCurrentUserRole() {
        String role = jwtTokenProvider.extractRole(getCurrentToken());
        return Role.valueOf(role);
    }

    public boolean isCurrentUserSuperAdmin() {
        return Role.SUPER_ADMIN == getCurrentUserRole();
    }

    private String getCurrentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getCredentials() == null) {
            throw new IllegalStateException("No authenticated JWT token found in SecurityContext");
        }
        return authentication.getCredentials().toString();
    }
}
