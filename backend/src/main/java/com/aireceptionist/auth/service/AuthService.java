package com.aireceptionist.auth.service;

import com.aireceptionist.auth.dto.AuthResponse;
import com.aireceptionist.auth.dto.LoginRequest;
import com.aireceptionist.auth.dto.RegisterRequest;
import com.aireceptionist.auth.entity.User;
import com.aireceptionist.auth.repository.UserRepository;
import com.aireceptionist.auth.security.JwtTokenProvider;
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
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already registered.");
        }
        if (request.getTenantId() == null) {
            throw new BadRequestException("Tenant ID is required for standard registration. SUPER_ADMIN users are created via a separate flow.");
        }
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .tenantId(request.getTenantId())
                .build();
        userRepository.save(user);

        return buildAuthResponse(user, "Registration successful.");
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + request.getEmail()));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadRequestException("Invalid credentials.");
        }

        return buildAuthResponse(user, "Login successful.");
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadRequestException("Invalid refresh token.");
        }

        Long userId = jwtTokenProvider.extractUserId(refreshToken);
        if (userId == null) {
            throw new BadRequestException("Refresh token missing userId claim.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String accessToken = jwtTokenProvider.generateAccessToken(user);
        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationMs())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .message("Token refreshed successfully.")
                .build();
    }

    private AuthResponse buildAuthResponse(User user, String message) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        return AuthResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .token(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationMs())
                .message(message)
                .build();
    }
}
