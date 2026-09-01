package com.aireceptionist.leads.repository;

import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    Page<Lead> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Lead> findByTenantIdAndStatus(UUID tenantId, LeadStatus status, Pageable pageable);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);

    long countByTenantIdAndErasedFalseAndCreatedAtAfter(UUID tenantId, Instant since);

    List<Lead> findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(UUID tenantId, Instant since);

    List<Lead> findByTenantIdAndErasedFalse(UUID tenantId);

    @Modifying
    @Query("UPDATE Lead l SET l.erased = true, l.customerName = null, l.phone = null, l.version = l.version + 1, "
            + "l.updatedAt = CURRENT_TIMESTAMP WHERE l.tenantId = :tenantId AND l.erased = false")
    int bulkEraseByTenantId(@Param("tenantId") UUID tenantId);
}
