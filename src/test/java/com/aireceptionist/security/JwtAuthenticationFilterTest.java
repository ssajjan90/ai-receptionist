package com.aireceptionist.security;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.security.JwtAuthenticationFilter;
import com.aireceptionist.common.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static JwtTokenProvider tokenProvider;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        tokenProvider = new JwtTokenProvider((RSAPrivateKey) keyPair.getPrivate(),
                (RSAPublicKey) keyPair.getPublic(), 60);
    }

    @Test
    void validTokenSetsSecurityAndTenantContextDuringRequestThenClearsThem() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider);
        String token = tokenProvider.generateToken("tenant-1", "user-1", "OWNER", "PRO");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/protected");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authenticationInsideChain = new AtomicReference<>();
        AtomicReference<String> tenantInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, new FilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                authenticationInsideChain.set(SecurityContextHolder.getContext().getAuthentication());
                tenantInsideChain.set(TenantContext.getCurrentTenant());
            }
        });

        assertThat(authenticationInsideChain.get()).isNotNull();
        assertThat(authenticationInsideChain.get().getName()).isEqualTo("user-1");
        assertThat(tenantInsideChain.get()).isEqualTo("tenant-1");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    void invalidTokenReturnsUnauthorizedAndDoesNotCallChain() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/protected");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"success\":false");
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"UNAUTHORIZED\"");
    }

    @Test
    void missingTokenContinuesWithoutAuthentication() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/protected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            chainCalled.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantContext.getCurrentTenant()).isNull();
        });

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }
}
