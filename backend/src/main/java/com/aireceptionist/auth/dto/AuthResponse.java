package com.aireceptionist.auth.dto;

import com.aireceptionist.auth.entity.User.Role;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {

    private String token;
    private String refreshToken;
    private long expiresIn;
    private String email;
    private String name;
    private Role role;
    private Long tenantId;
    private String message;
}
