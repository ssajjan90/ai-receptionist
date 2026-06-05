package com.aireceptionist.tenant.adapter.in.web;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.tenant.adapter.in.web.dto.AuthResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.CreateTenantRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.TenantResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.VerifyOtpRequest;
import com.aireceptionist.tenant.port.in.CompleteTenantOnboardingUseCase;
import com.aireceptionist.tenant.port.in.RegisterTenantUseCase;
import com.aireceptionist.tenant.port.in.ResendTenantOtpUseCase;
import com.aireceptionist.tenant.port.in.VerifyTenantOtpUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/tenants")
public class TenantController extends VersionedRestController {

    private final RegisterTenantUseCase registerTenantUseCase;
    private final VerifyTenantOtpUseCase verifyTenantOtpUseCase;
    private final ResendTenantOtpUseCase resendTenantOtpUseCase;
    private final CompleteTenantOnboardingUseCase completeTenantOnboardingUseCase;

    public TenantController(RegisterTenantUseCase registerTenantUseCase,
                            VerifyTenantOtpUseCase verifyTenantOtpUseCase,
                            ResendTenantOtpUseCase resendTenantOtpUseCase,
                            CompleteTenantOnboardingUseCase completeTenantOnboardingUseCase) {
        this.registerTenantUseCase = registerTenantUseCase;
        this.verifyTenantOtpUseCase = verifyTenantOtpUseCase;
        this.resendTenantOtpUseCase = resendTenantOtpUseCase;
        this.completeTenantOnboardingUseCase = completeTenantOnboardingUseCase;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TenantResponse>> register(@Valid @RequestBody CreateTenantRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(registerTenantUseCase.registerTenant(request)));
    }

    @PostMapping("/verify-otp")
    public ApiResponse<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ApiResponse.ok(verifyTenantOtpUseCase.verifyOtp(request));
    }

    @PostMapping("/resend-otp")
    public ApiResponse<Void> resendOtp(
            @RequestParam @Pattern(regexp = "^\\+[1-9]\\d{6,14}$") String ownerPhone
    ) {
        resendTenantOtpUseCase.requestNewOtp(ownerPhone);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{tenantId}/onboarding")
    public ApiResponse<OnboardingResponse> completeOnboarding(@PathVariable UUID tenantId,
                                                              @Valid @RequestBody OnboardingRequest request,
                                                              Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        return ApiResponse.ok(completeTenantOnboardingUseCase.completeOnboarding(tenantId, request));
    }

    private void validateTenantOwnership(UUID tenantId, Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication tenantAuthentication)) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated tenant context is required.");
        }

        if (!tenantId.toString().equals(tenantAuthentication.getTenantId())) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }
    }
}
