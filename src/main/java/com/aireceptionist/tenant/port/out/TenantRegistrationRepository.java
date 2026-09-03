package com.aireceptionist.tenant.port.out;

import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRegistrationRepository {

    boolean existsByEmail(String email);

    boolean existsByOwnerPhone(String ownerPhone);

    boolean existsByBusinessPhone(String businessPhone);

    Optional<BusinessTenant> findByOwnerPhone(String ownerPhone);

    Optional<BusinessTenant> findByPhoneNumberId(String phoneNumberId);

    Optional<BusinessTenant> findByBusinessPhone(String businessPhone);

    BusinessTenant save(BusinessTenant tenant);

    Optional<BusinessTenant> findById(UUID tenantId);

    List<BusinessTenant> findAllByStatus(TenantStatus status);
}
