package com.aireceptionist.tenant.application;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import org.springframework.dao.DataIntegrityViolationException;
import com.aireceptionist.tenant.port.in.ConnectTenantWhatsAppCommand;
import com.aireceptionist.tenant.port.in.ConnectTenantWhatsAppUseCase;
import com.aireceptionist.tenant.port.in.ConnectedTenantWhatsApp;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.tenant.port.in.GetTenantPhoneNumberIdUseCase;
import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantWhatsAppConnectionService implements ConnectTenantWhatsAppUseCase,
        ResolveTenantByWhatsAppPhoneUseCase, GetLiveTenantsUseCase, GetTenantPhoneNumberIdUseCase {

    private final TenantRegistrationRepository tenantRepository;

    public TenantWhatsAppConnectionService(TenantRegistrationRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional
    public ConnectedTenantWhatsApp connectWhatsApp(UUID tenantId, ConnectTenantWhatsAppCommand command) {
        BusinessTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found."));

        if (tenant.getStatus() != TenantStatus.ONBOARDING_COMPLETE && tenant.getStatus() != TenantStatus.LIVE) {
            throw new AuthorizationException(
                    "WRONG_ONBOARDING_STEP",
                    "WhatsApp connection is allowed only after onboarding is complete."
            );
        }

        tenantRepository.findByPhoneNumberId(command.phoneNumberId())
                .filter(existing -> !existing.getId().equals(tenantId))
                .ifPresent(existing -> {
                    throw new BusinessRuleException(
                            "PHONE_ALREADY_REGISTERED",
                            "WhatsApp phone number is already registered to another tenant."
                    );
                });

        tenant.connectWhatsApp(command.wabaId(), command.phoneNumberId());
        try {
            BusinessTenant savedTenant = tenantRepository.save(tenant);
            return new ConnectedTenantWhatsApp(
                    savedTenant.getId(),
                    savedTenant.getStatus().name(),
                    savedTenant.getPhoneNumberId(),
                    "WhatsApp business number connected"
            );
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException(
                    "PHONE_ALREADY_REGISTERED",
                    "WhatsApp phone number is already registered to another tenant."
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedTenantWhatsAppRoute> resolveByPhoneNumberId(String phoneNumberId) {
        return tenantRepository.findByPhoneNumberId(phoneNumberId)
                .map(tenant -> new ResolvedTenantWhatsAppRoute(
                        tenant.getId(), tenant.getPhoneNumberId(), tenant.getOwnerPhone()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getLiveTenantIds() {
        return tenantRepository.findAllByStatus(TenantStatus.LIVE).stream()
                .map(tenant -> tenant.getId())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPhoneNumberId(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.getPhoneNumberId());
    }
}
