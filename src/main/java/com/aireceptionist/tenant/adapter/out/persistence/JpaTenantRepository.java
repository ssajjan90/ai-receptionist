package com.aireceptionist.tenant.adapter.out.persistence;

import com.aireceptionist.tenant.domain.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaTenantRepository extends JpaRepository<BusinessTenantJpaEntity, UUID> {

    boolean existsByEmail(String email);

    boolean existsByOwnerPhone(String ownerPhone);

    boolean existsByBusinessPhone(String businessPhone);

    Optional<BusinessTenantJpaEntity> findByOwnerPhone(String ownerPhone);

    Optional<BusinessTenantJpaEntity> findByPhoneNumberId(String phoneNumberId);

    Optional<BusinessTenantJpaEntity> findByBusinessPhone(String businessPhone);

    List<BusinessTenantJpaEntity> findAllByStatus(TenantStatus status);
}
