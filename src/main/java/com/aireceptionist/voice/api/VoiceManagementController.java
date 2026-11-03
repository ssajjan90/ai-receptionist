package com.aireceptionist.voice.api;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.voice.service.ExotelCallService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Story 6.3 (AC3): owner-triggered live transfer (API path; owners on WhatsApp reply TRANSFER
 * instead — see {@code common.voice.VoiceTransferPort}). JWT with role OWNER required (falls under
 * {@code anyRequest().authenticated()}); {@code JwtAuthenticationFilter} sets the tenant, so RLS
 * makes another tenant's call SID a 404.
 */
@RestController
@RequestMapping("/v1/voice")
public class VoiceManagementController extends VersionedRestController {

    private final ExotelCallService exotelCallService;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;

    public VoiceManagementController(ExotelCallService exotelCallService, TenantOwnerPhonePort tenantOwnerPhonePort) {
        this.exotelCallService = exotelCallService;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
    }

    @PostMapping("/calls/{callSid}/transfer")
    public ApiResponse<Map<String, String>> transfer(@PathVariable String callSid, Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication tenantAuth) || tenantAuth.getTenantId() == null) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated tenant context is required.");
        }
        // Code review: only the business owner may bridge live customer calls.
        if (!"OWNER".equals(tenantAuth.getRole())) {
            throw new AuthorizationException("FORBIDDEN", "Only the business owner can transfer calls.");
        }
        String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenantAuth.getTenantId()).orElse(null);
        exotelCallService.transferCall(callSid, ownerPhone);
        return ApiResponse.ok(Map.of("callSid", callSid, "status", "TRANSFERRED"));
    }
}
