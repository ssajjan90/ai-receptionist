package com.aireceptionist.tenant.application;

import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.tenant.adapter.in.web.dto.AuthResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.CreateTenantRequest;
import com.aireceptionist.tenant.adapter.in.web.dto.TenantMapper;
import com.aireceptionist.tenant.adapter.in.web.dto.TenantResponse;
import com.aireceptionist.tenant.adapter.in.web.dto.VerifyOtpRequest;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantRegistrationPolicy;
import com.aireceptionist.tenant.port.in.RegisterTenantUseCase;
import com.aireceptionist.tenant.port.in.ResendTenantOtpUseCase;
import com.aireceptionist.tenant.port.in.VerifyTenantOtpUseCase;
import com.aireceptionist.tenant.port.out.OtpPort;
import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import com.aireceptionist.tenant.port.out.SubscriptionProvisioningPort;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import com.aireceptionist.tenant.port.out.TokenIssuerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service("tenantRegistrationService")
public class TenantRegistrationService implements RegisterTenantUseCase, VerifyTenantOtpUseCase, ResendTenantOtpUseCase {

    private static final String BASIC_TIER = "BASIC";
    private static final String OWNER_ROLE = "OWNER";

    private final TenantRegistrationRepository tenantRepository;
    private final OtpPort otpPort;
    private final OwnerNotificationPort ownerNotificationPort;
    private final TokenIssuerPort tokenIssuerPort;
    private final SubscriptionProvisioningPort subscriptionProvisioningPort;
    private final BCryptPasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantRegistrationService(TenantRegistrationRepository tenantRepository, OtpPort otpPort,
                                     OwnerNotificationPort ownerNotificationPort,
                                     TokenIssuerPort tokenIssuerPort,
                                     SubscriptionProvisioningPort subscriptionProvisioningPort,
                                     BCryptPasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.otpPort = otpPort;
        this.ownerNotificationPort = ownerNotificationPort;
        this.tokenIssuerPort = tokenIssuerPort;
        this.subscriptionProvisioningPort = subscriptionProvisioningPort;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public TenantResponse registerTenant(CreateTenantRequest request) {
        TenantRegistrationPolicy.ensureDedicatedBusinessPhone(request.ownerPhone(), request.businessPhone());
        if (tenantRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("EMAIL_ALREADY_REGISTERED", "Email is already registered.");
        }
        if (tenantRepository.existsByOwnerPhone(request.ownerPhone())) {
            throw new BusinessRuleException("PHONE_ALREADY_REGISTERED", "Owner phone is already registered.");
        }
        if (tenantRepository.existsByBusinessPhone(request.businessPhone())) {
            throw new BusinessRuleException("PHONE_ALREADY_REGISTERED", "Business phone is already registered.");
        }

        BusinessTenant tenant = BusinessTenant.register(
                request.businessName(),
                request.ownerName(),
                request.ownerPhone(),
                request.businessPhone(),
                request.email(),
                passwordEncoder.encode(request.password())
        );
        BusinessTenant savedTenant = tenantRepository.save(tenant);
        entityManager.flush();
        subscriptionProvisioningPort.provisionBasicSubscription(savedTenant.getId());

        String otp = otpPort.generateAndStore(savedTenant.getOwnerPhone());
        ownerNotificationPort.sendOtp(savedTenant.getOwnerPhone(), otp);
        return TenantMapper.toResponse(savedTenant);
    }

    @Override
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        BusinessTenant tenant = tenantRepository.findByOwnerPhone(request.ownerPhone())
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found for owner phone."));

        otpPort.verifyAndConsume(request.ownerPhone(), request.otp());
        tenant.activate();
        BusinessTenant savedTenant = tenantRepository.save(tenant);
        return new AuthResponse(
                generateJwt(savedTenant),
                savedTenant.getId(),
                OWNER_ROLE,
                BASIC_TIER
        );
    }

    @Override
    public void requestNewOtp(String ownerPhone) {
        BusinessTenant tenant = tenantRepository.findByOwnerPhone(ownerPhone)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found for owner phone."));
        otpPort.invalidateExisting(ownerPhone);
        String otp = otpPort.generateAndStore(ownerPhone);
        ownerNotificationPort.sendOtp(tenant.getOwnerPhone(), otp);
    }

    private String generateJwt(BusinessTenant tenant) {
        try {
            return tokenIssuerPort.issueToken(
                    tenant.getId().toString(),
                    tenant.getId().toString(),
                    OWNER_ROLE,
                    BASIC_TIER
            );
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Unable to generate tenant JWT", ex);
        }
    }
}
