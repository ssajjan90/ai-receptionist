package com.aireceptionist.whatsapp;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.tenant.application.TenantWhatsAppConnectionService;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.port.in.ConnectTenantWhatsAppCommand;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppConnectionServiceTest {

    private final TenantRegistrationRepository tenantRepository = mock(TenantRegistrationRepository.class);
    private final TenantWhatsAppConnectionService service = new TenantWhatsAppConnectionService(tenantRepository);

    @Test
    void connectWhatsAppRejectsWrongOnboardingStep() {
        BusinessTenant tenant = BusinessTenant.register(
                "Shop",
                "Owner",
                "+919876543210",
                "+919876543211",
                "owner@example.com",
                "hash"
        );
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> service.connectWhatsApp(tenant.getId(), command("PHONE_1")))
                .isInstanceOf(AuthorizationException.class)
                .extracting("errorCode")
                .isEqualTo("WRONG_ONBOARDING_STEP");

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void connectWhatsAppRejectsPhoneNumberRegisteredToAnotherTenant() {
        BusinessTenant tenant = onboardedTenant("owner@example.com", "+919876543210", "+919876543211");
        BusinessTenant otherTenant = onboardedTenant("other@example.com", "+919876543212", "+919876543213");
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantRepository.findByPhoneNumberId("PHONE_1")).thenReturn(Optional.of(otherTenant));

        assertThatThrownBy(() -> service.connectWhatsApp(tenant.getId(), command("PHONE_1")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode")
                .isEqualTo("PHONE_ALREADY_REGISTERED");

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void connectWhatsAppStoresIdentifiersAndMovesTenantLive() {
        BusinessTenant tenant = onboardedTenant("owner@example.com", "+919876543210", "+919876543211");
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantRepository.findByPhoneNumberId("PHONE_1")).thenReturn(Optional.empty());
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.connectWhatsApp(tenant.getId(), command("PHONE_1"));

        assertThat(response.status()).isEqualTo("LIVE");
        assertThat(response.phoneNumberId()).isEqualTo("PHONE_1");
        assertThat(tenant.getWabaId()).isEqualTo("WABA_1");
        assertThat(tenant.getPhoneNumberId()).isEqualTo("PHONE_1");
        assertThat(tenant.getWhatsappConnectedAt()).isNotNull();
        verify(tenantRepository).save(tenant);
    }

    private BusinessTenant onboardedTenant(String email, String ownerPhone, String businessPhone) {
        BusinessTenant tenant = BusinessTenant.register(
                "Shop",
                "Owner",
                ownerPhone,
                businessPhone,
                email,
                "hash"
        );
        tenant.activate();
        tenant.completeOnboarding("Shop", "Bengaluru", "9am-9pm", "en");
        return tenant;
    }

    private ConnectTenantWhatsAppCommand command(String phoneNumberId) {
        return new ConnectTenantWhatsAppCommand("WABA_1", phoneNumberId, "+91 98765 43210");
    }
}
