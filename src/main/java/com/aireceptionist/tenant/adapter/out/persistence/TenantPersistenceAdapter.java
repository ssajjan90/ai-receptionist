package com.aireceptionist.tenant.adapter.out.persistence;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TenantPersistenceAdapter implements TenantRegistrationRepository, TenantNamePort, TenantOwnerPhonePort {

    private final JpaTenantRepository repository;

    public TenantPersistenceAdapter(JpaTenantRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByEmail(String email) {
        return repository.existsByEmail(email);
    }

    @Override
    public boolean existsByOwnerPhone(String ownerPhone) {
        return repository.existsByOwnerPhone(ownerPhone);
    }

    @Override
    public boolean existsByBusinessPhone(String businessPhone) {
        return repository.existsByBusinessPhone(businessPhone);
    }

    @Override
    public Optional<BusinessTenant> findByOwnerPhone(String ownerPhone) {
        return repository.findByOwnerPhone(ownerPhone).map(BusinessTenantJpaEntity::toDomain);
    }

    @Override
    public Optional<BusinessTenant> findByPhoneNumberId(String phoneNumberId) {
        return repository.findByPhoneNumberId(phoneNumberId).map(BusinessTenantJpaEntity::toDomain);
    }

    @Override
    public Optional<BusinessTenant> findByBusinessPhone(String businessPhone) {
        return repository.findByBusinessPhone(businessPhone).map(BusinessTenantJpaEntity::toDomain);
    }

    @Override
    public BusinessTenant save(BusinessTenant tenant) {
        return repository.save(BusinessTenantJpaEntity.fromDomain(tenant)).toDomain();
    }

    @Override
    public Optional<BusinessTenant> findById(UUID tenantId) {
        return repository.findById(tenantId).map(BusinessTenantJpaEntity::toDomain);
    }

    @Override
    public List<BusinessTenant> findAllByStatus(TenantStatus status) {
        return repository.findAllByStatus(status).stream()
                .map(BusinessTenantJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<String> getBusinessName(String tenantId) {
        try {
            return repository.findById(UUID.fromString(tenantId))
                    .map(BusinessTenantJpaEntity::toDomain)
                    .map(BusinessTenant::getBusinessName);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getOwnerPhone(String tenantId) {
        try {
            return repository.findById(UUID.fromString(tenantId))
                    .map(BusinessTenantJpaEntity::toDomain)
                    .map(BusinessTenant::getOwnerPhone);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
