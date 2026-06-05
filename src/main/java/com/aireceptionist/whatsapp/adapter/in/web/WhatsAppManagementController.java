package com.aireceptionist.whatsapp.adapter.in.web;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.tenant.port.in.ConnectTenantWhatsAppCommand;
import com.aireceptionist.tenant.port.in.ConnectTenantWhatsAppUseCase;
import com.aireceptionist.tenant.port.in.ConnectedTenantWhatsApp;
import com.aireceptionist.whatsapp.adapter.in.web.dto.WhatsAppConnectRequest;
import com.aireceptionist.whatsapp.adapter.in.web.dto.WhatsAppConnectResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/tenants")
public class WhatsAppManagementController extends VersionedRestController {

    private final ConnectTenantWhatsAppUseCase connectTenantWhatsAppUseCase;

    public WhatsAppManagementController(ConnectTenantWhatsAppUseCase connectTenantWhatsAppUseCase) {
        this.connectTenantWhatsAppUseCase = connectTenantWhatsAppUseCase;
    }

    @PostMapping("/{tenantId}/whatsapp/connect")
    public ApiResponse<WhatsAppConnectResponse> connect(@PathVariable UUID tenantId,
                                                        @Valid @RequestBody WhatsAppConnectRequest request,
                                                        Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        ConnectedTenantWhatsApp connected = connectTenantWhatsAppUseCase.connectWhatsApp(
                tenantId,
                new ConnectTenantWhatsAppCommand(request.wabaId(), request.phoneNumberId(), request.displayPhoneNumber())
        );
        return ApiResponse.ok(new WhatsAppConnectResponse(
                connected.tenantId(),
                connected.status(),
                connected.phoneNumberId(),
                connected.message()
        ));
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
