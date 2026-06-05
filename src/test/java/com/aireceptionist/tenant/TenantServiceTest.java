package com.aireceptionist.tenant;

import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.tenant.adapter.in.web.dto.CreateTenantRequest;
import com.aireceptionist.tenant.application.TenantRegistrationService;
import com.aireceptionist.tenant.port.out.OtpPort;
import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import com.aireceptionist.tenant.port.out.SubscriptionProvisioningPort;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import com.aireceptionist.tenant.port.out.TokenIssuerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    private final TenantRegistrationRepository tenantRepository = mock(TenantRegistrationRepository.class);
    private final OtpPort otpService = mock(OtpPort.class);
    private final OwnerNotificationPort whatsAppNotificationService = mock(OwnerNotificationPort.class);
    private final TokenIssuerPort tokenIssuerPort = mock(TokenIssuerPort.class);
    private final SubscriptionProvisioningPort subscriptionProvisioningPort = mock(SubscriptionProvisioningPort.class);
    private final TenantRegistrationService tenantService =
            new TenantRegistrationService(
                    tenantRepository,
                    otpService,
                    whatsAppNotificationService,
                    tokenIssuerPort,
                    subscriptionProvisioningPort,
                    new BCryptPasswordEncoder()
            );

    @Test
    void registerTenantRejectsSameOwnerAndBusinessPhone() {
        CreateTenantRequest request = validRequest("+919876543210", "+919876543210", "owner@example.com");

        assertThatThrownBy(() -> tenantService.registerTenant(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo("SAME_PHONE_CONFLICT");

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void registerTenantRejectsDuplicateEmail() {
        CreateTenantRequest request = validRequest("+919876543210", "+919876543211", "owner@example.com");
        when(tenantRepository.existsByEmail("owner@example.com")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.registerTenant(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void registerTenantPersistsTenantAndSendsOtp() {
        CreateTenantRequest request = validRequest("+919876543210", "+919876543211", "owner@example.com");
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(otpService.generateAndStore("+919876543210")).thenReturn("123456");

        tenantService.registerTenant(request);

        verify(tenantRepository).save(any());
        verify(subscriptionProvisioningPort).provisionBasicSubscription(any());
        verify(otpService).generateAndStore("+919876543210");
        verify(whatsAppNotificationService).sendOtp("+919876543210", "123456");
    }

    private CreateTenantRequest validRequest(String ownerPhone, String businessPhone, String email) {
        return new CreateTenantRequest(
                "Suresh Stores",
                "Suresh",
                ownerPhone,
                businessPhone,
                email,
                "password123"
        );
    }
}
