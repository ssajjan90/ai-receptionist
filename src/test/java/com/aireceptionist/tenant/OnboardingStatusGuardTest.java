package com.aireceptionist.tenant;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.tenant.adapter.in.web.dto.OnboardingRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.ProductEntry;
import com.aireceptionist.tenant.application.TenantOnboardingService;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingStatusGuardTest {

    private final TenantRegistrationRepository tenantRepository = mock(TenantRegistrationRepository.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TenantOnboardingService service =
            new TenantOnboardingService(tenantRepository, knowledgeBaseService, eventPublisher);

    @Test
    void onboardingRejectsTenantThatIsNotActive() {
        UUID tenantId = UUID.randomUUID();
        BusinessTenant tenant = BusinessTenant.register(
                "Shop",
                "Owner",
                "+919876543210",
                "+919876543211",
                "owner@example.com",
                "hash"
        );
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> service.completeOnboarding(tenantId, request()))
                .isInstanceOf(AuthorizationException.class)
                .extracting("errorCode")
                .isEqualTo("ONBOARDING_NOT_ALLOWED");

        verify(knowledgeBaseService, never()).bulkUpsertProducts(any(), any());
    }

    private OnboardingRequest request() {
        return new OnboardingRequest(
                "Suresh Stores",
                "Bengaluru",
                "9am-9pm",
                null,
                List.of(new ProductEntry("Tea", "20")),
                List.of()
        );
    }
}
