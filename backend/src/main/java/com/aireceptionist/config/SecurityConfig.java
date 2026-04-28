package com.aireceptionist.config;

import com.aireceptionist.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        // /api/chat is intentionally public so external chat/webhook callers can invoke it without JWT.
                        .requestMatchers(HttpMethod.POST, "/api/chat").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tenants").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tenants").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/tenants/*").hasRole("SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/tenants/*").hasAnyRole("SUPER_ADMIN", "TENANT_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/tenants/*").hasAnyRole("SUPER_ADMIN", "TENANT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tenants/*/knowledge").hasAnyRole("SUPER_ADMIN", "TENANT_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/knowledge/*").hasAnyRole("SUPER_ADMIN", "TENANT_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/knowledge/*").hasAnyRole("SUPER_ADMIN", "TENANT_ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
