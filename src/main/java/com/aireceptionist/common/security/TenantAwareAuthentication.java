package com.aireceptionist.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class TenantAwareAuthentication extends AbstractAuthenticationToken {

    private final String tenantId;
    private final String userId;
    private final String role;
    private final String tier;

    public TenantAwareAuthentication(String tenantId, String userId, String role, String tier) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
        this.tier = tier;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getTier() {
        return tier;
    }
}
