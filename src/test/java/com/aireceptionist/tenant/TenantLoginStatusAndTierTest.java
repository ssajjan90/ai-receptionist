package com.aireceptionist.tenant;

import com.aireceptionist.tenant.adapter.in.web.dto.VerifyOtpRequest;
import com.aireceptionist.tenant.application.TenantRegistrationService;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import com.aireceptionist.tenant.port.out.OtpPort;
import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import com.aireceptionist.tenant.port.out.SubscriptionProvisioningPort;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import com.aireceptionist.tenant.port.out.TokenIssuerPort;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Epic 7 code review: login (verifyOtp) must issue the tenant's real tier in the JWT, and must
 * never reset a non-pending tenant's status (it used to set ACTIVE unconditionally, letting a
 * suspended owner lift their own suspension by logging in).
 */
class TenantLoginStatusAndTierTest {

    private final TenantRegistrationRepository repository = mock(TenantRegistrationRepository.class);
    private final TokenIssuerPort tokenIssuer = mock(TokenIssuerPort.class);
    private final TenantRegistrationService service = new TenantRegistrationService(repository, mock(OtpPort.class),
            mock(OwnerNotificationPort.class), tokenIssuer, mock(SubscriptionProvisioningPort.class),
            new BCryptPasswordEncoder());

    private BusinessTenant tenant(TenantStatus status, String tier) {
        Instant now = Instant.now();
        return BusinessTenant.restore(UUID.randomUUID(), "Shop", "Owner", "+919800000000", "+918000000000",
                "o@example.com", "hash", status, tier, "en", null, null, null, null, null, null, null, now, now, null);
    }

    private BusinessTenant login(BusinessTenant tenant) {
        when(repository.findByOwnerPhone("+919800000000")).thenReturn(Optional.of(tenant));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenIssuer.issueToken(anyString(), anyString(), anyString(), anyString())).thenReturn("jwt");
        service.verifyOtp(new VerifyOtpRequest("+919800000000", "123456"));
        return tenant;
    }

    @Test
    void jwtCarriesTheTenantsRealTier() {
        BusinessTenant t = login(tenant(TenantStatus.LIVE, "PRO"));
        verify(tokenIssuer).issueToken(eq(t.getId().toString()), anyString(), eq("OWNER"), eq("PRO"));
    }

    @ParameterizedTest
    @EnumSource(value = TenantStatus.class, names = {"LIVE", "SUSPENDED", "PAYMENT_SUSPENDED", "TERMINATED", "ONBOARDING_COMPLETE"})
    void loginNeverResetsAnEstablishedTenantsStatus(TenantStatus status) {
        assertThat(login(tenant(status, "BASIC")).getStatus()).isEqualTo(status);
    }

    @Test
    void firstVerificationActivatesAPendingTenant() {
        assertThat(login(tenant(TenantStatus.PENDING_VERIFICATION, "BASIC")).getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }
}
