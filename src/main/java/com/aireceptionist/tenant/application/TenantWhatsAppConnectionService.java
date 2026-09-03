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
import com.aireceptionist.tenant.port.in.GetTenantStatusUseCase;
import com.aireceptionist.tenant.port.in.ResolveTenantByBusinessPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantVoiceRoute;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.tenant.port.in.TenantDataRightsUseCase;
import com.aireceptionist.tenant.port.in.TenantLifecycleUseCase;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantWhatsAppConnectionService implements ConnectTenantWhatsAppUseCase,
        ResolveTenantByWhatsAppPhoneUseCase, ResolveTenantByBusinessPhoneUseCase, GetLiveTenantsUseCase,
        GetTenantPhoneNumberIdUseCase, GetTenantStatusUseCase, TenantLifecycleUseCase {

    private final TenantRegistrationRepository tenantRepository;
    private final TenantDataRightsUseCase tenantDataRightsUseCase;

    public TenantWhatsAppConnectionService(TenantRegistrationRepository tenantRepository,
                                           TenantDataRightsUseCase tenantDataRightsUseCase) {
        this.tenantRepository = tenantRepository;
        this.tenantDataRightsUseCase = tenantDataRightsUseCase;
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

    /** Story 6.1 (AC2). */
    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedTenantVoiceRoute> resolveByBusinessPhone(String businessPhone) {
        return tenantRepository.findByBusinessPhone(businessPhone)
                .map(tenant -> new ResolvedTenantVoiceRoute(tenant.getId(), tenant.getTier()));
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

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getStatus(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.getStatus().name());
    }

    /** Story 5.2 (AC1). */
    @Override
    @Transactional
    public void suspend(UUID tenantId) {
        BusinessTenant tenant = loadTenant(tenantId);
        tenant.suspend();
        tenantRepository.save(tenant);
    }

    /** Story 5.2 (AC2). */
    @Override
    @Transactional
    public void reactivate(UUID tenantId) {
        BusinessTenant tenant = loadTenant(tenantId);
        tenant.reactivate();
        tenantRepository.save(tenant);
    }

    /** Story 5.2 (AC3) — schedules the 30-day retention window; TenantRetentionService does the actual erasure. */
    @Override
    @Transactional
    public void terminate(UUID tenantId) {
        BusinessTenant tenant = loadTenant(tenantId);
        tenant.terminate();
        tenantRepository.save(tenant);
    }

    /**
     * Story 5.5 (AC3, AC4, AC5) — DPDP right-to-erasure, immediate (unlike {@link #terminate}'s
     * 30-day grace period). {@code @Transactional} makes the data deletion and the status
     * transition atomic per AC4's explicit "single DB transaction, rolls back fully on any
     * error" requirement: {@code eraseTenantData} joins this same transaction (its own
     * JdbcTemplate calls bind to the active Spring transaction's connection), so a failure at
     * either step rolls back both. {@code eraseTenantData} already writes the AC5-required
     * {@code TENANT_DATA_ERASED} audit entry internally (see {@code AuditLogTenantAuditAdapter}).
     * Story 5.5 code review: guards against a retried/double-submitted call on an already-{@code
     * ERASED} tenant — without this, a second call would re-run (harmless, idempotent) deletion
     * against already-empty tables but write a second {@code TENANT_DATA_ERASED} audit entry.
     */
    @Override
    @Transactional
    public void eraseNow(UUID tenantId) {
        BusinessTenant tenant = loadTenant(tenantId);
        if (tenant.getStatus() == TenantStatus.ERASED) {
            return;
        }
        tenantDataRightsUseCase.eraseTenantData(tenantId);
        tenant.markErased();
        tenantRepository.save(tenant);
    }

    private BusinessTenant loadTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found: " + tenantId));
    }
}
