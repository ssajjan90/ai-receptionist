package com.aireceptionist.auth.security;

import com.aireceptionist.auth.entity.User;
import com.aireceptionist.auth.repository.UserRepository;
import com.aireceptionist.common.exception.ForbiddenException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final UserRepository userRepository;

    public User getCurrentUserOrThrow() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResourceNotFoundException("Authenticated user not found.");
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + authentication.getName()));
    }

    public void validateTenantAccess(Long tenantId) {
        User user = getCurrentUserOrThrow();
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            return;
        }
        if (user.getTenantId() == null || !user.getTenantId().equals(tenantId)) {
            throw new ForbiddenException("You do not have access to the requested tenant.");
        }
    }
}
