package com.aireceptionist.config;

import com.aireceptionist.common.ratelimit.RateLimitFilter;
import com.aireceptionist.common.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/v1/tenants/register", "/v1/tenants/verify-otp",
                            "/v1/tenants/resend-otp", "/webhooks/**", "/actuator/health", "/actuator/info",
                            "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // /v1/admin/** role enforcement intentionally lives in @PreAuthorize on
                    // AdminController, not here: a filter-chain-level requestMatcher rejects
                    // before DispatcherServlet ever dispatches, bypassing GlobalExceptionHandler
                    // and breaking the app's ApiResponse envelope for the 403 body. Leaving
                    // /v1/admin/** to fall through to .anyRequest().authenticated() below still
                    // requires authentication; @PreAuthorize then enforces the role with a
                    // consistent response (see code review of story 5-1, 2026-09-01).
                    .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
