package com.aireceptionist.auth.service;

import com.aireceptionist.auth.dto.AuthResponse;
import com.aireceptionist.auth.dto.LoginRequest;
import com.aireceptionist.auth.dto.RegisterRequest;
import com.aireceptionist.auth.entity.User;
import com.aireceptionist.auth.repository.UserRepository;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already registered.");
        }
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .tenantId(request.getTenantId())
                .build();
        userRepository.save(user);

        return AuthResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .message("Registration successful. JWT integration coming soon.")
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + request.getEmail()));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadRequestException("Invalid credentials.");
        }

        // TODO: generate and return a JWT token here
        return AuthResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .token("JWT_TOKEN_PLACEHOLDER — wire in Spring Security JWT here")
                .message("Login successful.")
                .build();
    }
}
