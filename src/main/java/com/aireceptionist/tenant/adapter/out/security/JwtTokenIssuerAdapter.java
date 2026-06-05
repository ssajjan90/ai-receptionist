package com.aireceptionist.tenant.adapter.out.security;

import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.tenant.port.out.TokenIssuerPort;
import com.nimbusds.jose.JOSEException;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenIssuerAdapter implements TokenIssuerPort {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtTokenIssuerAdapter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public String issueToken(String tenantId, String userId, String role, String tier) {
        try {
            return jwtTokenProvider.generateToken(tenantId, userId, role, tier);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to generate JWT", ex);
        }
    }
}
